/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import static com.fr3ts0n.ecu.gui.androbd.CommService.log;
import static com.fr3ts0n.ecu.prot.obd.ObdProt.lastRxMsg;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.IndexedProcessVar;
import com.fr3ts0n.pvs.PvList;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Task to save measurements.
 */
public class FileHelper {

	/** Date Formatter used to generate file name */
	@SuppressLint("SimpleDateFormat")
	private static final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss");
	private static final String TAG = "FileHelper";

	private final Context context;
	private final ElmProt elm;
	private Map<String, IndexedProcessVar> pvs = new HashMap<>();

	private boolean isSaving = false;
	private boolean isPaused = false;
	private final Handler handler = new Handler();
	private ProgressDialog progress;

	/** Date Formatter used to generate file name */
	public FileHelper(Context context) {
		this.context = context;
		this.elm = CommService.elm;
	}

	public void setPvs(PvList pvs) {
		this.pvs = pvs;
	}

	public static String getPath(Context context) {
		File dir = context.getExternalFilesDir(null);
		return (dir != null) ? dir.getAbsolutePath() : context.getFilesDir().getAbsolutePath();
	}

	public static String getFileName() {
		return dateFmt.format(System.currentTimeMillis());
	}

	public void pauseSaving() {
		isPaused = true;
	}

	public void resumeSaving() {
		if (isPaused) {
			isPaused = false;
			saveDataThreaded();
		}
	}

	public void saveDataThreaded() {
		if (isSaving) return;

		isSaving = true;
		final String mFileName = getFileName() + ".csv";

		progress = new ProgressDialog(context);
		progress.setMessage("Saving data to: " + mFileName);
		progress.setCancelable(false);
		progress.setButton(DialogInterface.BUTTON_NEGATIVE, "Stop", (dialog, which) -> stopSaving());
		progress.show();

		handler.post(new Runnable() {
			@Override
			public void run() {
				if (!isPaused && isSaving) {
					saveData(mFileName);
					handler.postDelayed(this, 1500);
				} else {
					progress.dismiss();
				}
			}
		});
	}

	public void stopSaving() {
		isSaving = false;
		handler.removeCallbacksAndMessages(null);
		if (progress != null && progress.isShowing()) {
			progress.dismiss();
		}
	}

	void saveData(String fileName) {
		if (!isSaving) {
			Log.e(TAG, "Data saving is not active.");
			return;
		}

		// Directory setup
		File saveDir = new File(Environment.getExternalStorageDirectory(), "Documents");
		if (!saveDir.exists() && !saveDir.mkdirs()) {
			Log.e(TAG, "Failed to create save directory.");
			Toast.makeText(context, "Error: Cannot create save directory.", Toast.LENGTH_SHORT).show();
			return;
		}

		String fullPath = saveDir.getAbsolutePath() + File.separator + fileName;

		try (FileWriter writer = new FileWriter(fullPath, true)) {
			File file = new File(fullPath);
			if (file.length() == 0) {
				// Write headers if file is empty
				writer.write("Timestamp,Vehicle Speed (km/h),RPM,Fuel Consumption (L/100km),Gear Position,Brake Status,Steering Wheel Angle (degrees)\n");
			}

			// Fetch data
			String timestamp = dateFmt.format(System.currentTimeMillis());
			String speed = getData("010D"); // Vehicle Speed
			String rpm = getData("010C"); // Engine RPM
			String engineLoad = getData("0104"); // Engine Load (%)
			/*String throttlePosition = getData("0111");*/ // Throttle Position (%)
			String maf = getData("0110"); // MAF (g/s)

			// Derived data
			String fuelConsumption = calculateFuelConsumption(maf, speed);
			String gearPosition = calculateGearPosition(speed, rpm);// Use MAF for better accuracy
			/*String gearPosition = calculateGearPosition(speed, rpm, throttlePosition); */// Refined with throttle position
			String brakeStatus = calculateBrakeStatus(speed); // Derived from speed
			String steeringAngle = calculateSteeringAngle(speed, rpm, engineLoad); // Refined with engine load

			// Write data row
			writer.write(String.format("%s,%s,%s,%s,%s,%s,%s\n",
					timestamp,
					speed != null ? speed : "N/A",
					rpm != null ? rpm : "N/A",
					fuelConsumption != null ? fuelConsumption : "N/A",
					gearPosition != null ? gearPosition : "N/A",
					brakeStatus != null ? brakeStatus : "N/A",
					steeringAngle != null ? steeringAngle : "N/A"));

			writer.flush();
			Log.d(TAG, "Data saved successfully to: " + fullPath);
			Toast.makeText(context, "Data saved to: " + fullPath, Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			Log.e(TAG, "Error saving data", e);
			Toast.makeText(context, "Error saving data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}

// Helper methods for derived calculations

	private String calculateFuelConsumption(String maf, String speed) {
		try {
			double mafValue = Double.parseDouble(maf); // MAF in g/s
			double speedValue = Double.parseDouble(speed); // Speed in km/h

			// Convert MAF to fuel consumption (L/100km)
			// Assuming stoichiometric ratio for gasoline (14.7:1 air to fuel) and gasoline density (720 g/L)
			double fuelRate = (mafValue / 14.7) / 720.0 * 3600.0; // Fuel rate in L/h
			double fuelConsumption = fuelRate / Math.max(1, speedValue) * 100.0; // Convert to L/100km

			return String.format("%.2f", fuelConsumption);
		} catch (NumberFormatException e) {
			Log.e(TAG, "Error calculating fuel consumption", e);
			return "Error";
		}
	}
	private String calculateGearPosition(String speed, String rpm) {
		try {
			int speedValue = Integer.parseInt(speed);
			int rpmValue = Integer.parseInt(rpm);

			// Approximate gear ratio calculation (values can vary by car model)
			double gearRatio = (rpmValue / 1000.0) / Math.max(1, speedValue);

			if (gearRatio < 0.5) return "5"; // Example thresholds for gear ratios
			else if (gearRatio < 1.0) return "4";
			else if (gearRatio < 1.5) return "3";
			else if (gearRatio < 2.0) return "2";
			else return "1";
		} catch (NumberFormatException e) {
			Log.e(TAG, "Error calculating gear position", e);
			return "Error";
		}
	}


	/*private String calculateGearPosition(String speed, String rpm, String throttlePosition) {
		try {
			double speedValue = Double.parseDouble(speed);
			double rpmValue = Double.parseDouble(rpm);
			double throttleValue = Double.parseDouble(throttlePosition);

			// Approximate gear ratio calculation
			double gearRatio = rpmValue / Math.max(1, speedValue);

			// Adjust thresholds based on throttle position (aggressive driving implies lower gear)
			if (throttleValue > 70) gearRatio *= 1.2;

			if (gearRatio < 0.5) return "5"; // Example thresholds for gear ratios
			else if (gearRatio < 1.0) return "4";
			else if (gearRatio < 1.5) return "3";
			else if (gearRatio < 2.0) return "2";
			else return "1";
		} catch (NumberFormatException e) {
			Log.e(TAG, "Error calculating gear position", e);
			return "Error";
		}
	}*/

	private String calculateBrakeStatus(String speed) {
		try {
			double speedValue = Double.parseDouble(speed);

			// Assumption: brake engaged when speed is close to 0
			return (speedValue < 1) ? "Engaged" : "Not Engaged";
		} catch (NumberFormatException e) {
			Log.e(TAG, "Error calculating brake status", e);
			return "Error";
		}
	}

	private String calculateSteeringAngle(String speed, String rpm, String engineLoad) {
		try {
			double speedValue = Double.parseDouble(speed);
			double rpmValue = Double.parseDouble(rpm);
			double engineLoadValue = Double.parseDouble(engineLoad);

			// Simulate steering angle using speed, RPM, and engine load
			double angle = (Math.sin(speedValue / 100.0) + engineLoadValue / 100.0) * (rpmValue / 1000.0);
			return String.format("%.1f", angle);
		} catch (NumberFormatException e) {
			Log.e(TAG, "Error calculating steering angle", e);
			return "Error";
		}
	}

	private String getData(String pid) {
		try {
			// Send the command for the PID
			sendPidRequest(pid);

			// Wait for the response
			Thread.sleep(500); // Adjust delay as needed for your device

			// Process the response
			String response = processResponse(pid);
			return response != null ? response : "Error";
		} catch (InterruptedException e) {
			Log.e(TAG, "Interrupted while waiting for PID response: " + pid, e);
			return "Error";
		} catch (Exception e) {
			Log.e(TAG, "Error while getting data for PID: " + pid, e);
			return "Error";
		}
	}

	private void sendPidRequest(String pid) {
		try {
			String command = "01" + pid.substring(2); // Format PID for ELM327
			elm.sendTelegram(command.toCharArray());
			Log.d(TAG, "Sent PID request: " + command);
		} catch (Exception e) {
			Log.e(TAG, "Failed to send PID request: " + pid, e);
		}
	}

	@SuppressLint("DefaultLocale")
    private String processResponse(String pid) {
		String response = lastRxMsg.trim(); // Get the last received message

		// Ensure that the response is valid
		if (response == null || response.length() < 5) {
			Log.e(TAG, "Invalid response received: " + response);
			return "Error";
		}

		// Example for Vehicle Speed (PID 010D)
		if (pid.equals("010D")) {  // Vehicle speed
			if (response.length() >= 6) {
				String speedHex = response.substring(4, 6);  // Extract "1A"
				try {
					int speed = Integer.parseInt(speedHex, 16);  // Convert to decimal (26 km/h)
					return String.valueOf(speed);
				} catch (NumberFormatException e) {
					Log.e(TAG, "Error parsing speed: " + speedHex, e);
				}
			}
		}

		// Example for Engine RPM (PID 010C)
		if (pid.equals("010C")) {  // RPM
			if (response.length() >= 6) {
				String rpmHex = response.substring(4, 8);  // Extract "1234"
				try {
					int rpm = Integer.parseInt(rpmHex, 16);  // Convert to decimal
					return String.valueOf(rpm);
				} catch (NumberFormatException e) {
					Log.e(TAG, "Error parsing RPM: " + rpmHex, e);
				}
			}
		}

		/*if (pid.equals("0111")) {  // Throttle Position
			if (response.length() >= 6) {
				String throttleHex = response.substring(4, 6);  // Extract byte
				try {
					int throttle = Integer.parseInt(throttleHex, 16) * 100 / 255;  // Scale to percentage
					return String.format("%d%%", throttle);  // Return throttle position
				} catch (NumberFormatException e) {
					Log.e(TAG, "Error parsing Throttle Position: " + throttleHex, e);
				}
			}
		}*/

		if (pid.equals("0110")) {  // MAF (Mass Air Flow)
			if (response.length() >= 8) {  // Ensure we have enough bytes
				String mafHex = response.substring(4, 8);  // Extract "AABB"
				try {
					int maf = Integer.parseInt(mafHex.substring(0, 2), 16) * 256
							+ Integer.parseInt(mafHex.substring(2, 4), 16);  // Combine A and B
					double mafValue = maf / 100.0;  // Scale to g/s
					return String.format("%.2f", mafValue);  // Return MAF in g/s
				} catch (NumberFormatException e) {
					Log.e(TAG, "Error parsing MAF: " + mafHex, e);
				}
			}
		}

		if (pid.equals("0104")) {  // Engine Load
			if (response.length() >= 6) {  // Ensure we have enough bytes
				String loadHex = response.substring(4, 6);  // Extract "A"
				try {
					int loadValue = Integer.parseInt(loadHex, 16);  // Convert "A" from hex to decimal
					double engineLoad = (loadValue * 100.0) / 255.0;  // Scale to percentage
					return String.format("%.2f", engineLoad);  // Return Engine Load as a percentage
				} catch (NumberFormatException e) {
					Log.e(TAG, "Error parsing Engine Load: " + loadHex, e);
				}
			}
		}



		// Add more PIDs as needed (Fuel, Gear Position, Brake Status, Steering Angle, etc.)

		Log.e(TAG, "Unsupported PID or invalid response: " + pid);
		return "Error";  // Return error if the PID is unsupported or the response is invalid
	}






	/**
	 * Load all data in a independent thread
	 * @param uri Uri of ile to be loaded
	 */
	synchronized void loadDataThreaded(final Uri uri,
	                                   final Handler reportTo)
	{
		// create progress dialog
		progress = ProgressDialog.show(context,
		                               context.getString(R.string.loading_data),
		                               uri.getPath(),
		                               true);

		Thread loadTask = new Thread()
		{
			public void run()
			{
				Looper.prepare();
				loadData(uri);
				progress.dismiss();
				reportTo.sendMessage(reportTo.obtainMessage(MainActivity.MESSAGE_FILE_READ));
				Looper.loop();
			}
		};
		loadTask.start();
	}

	/**
	 * Load data from file into data structures
	 *
	 * @param uri URI of file to be loaded
	 */
	@SuppressLint("DefaultLocale")
	@SuppressWarnings("UnusedReturnValue")
	private synchronized int loadData(final Uri uri)
	{
		int numBytesLoaded = 0;
		String msg;
		InputStream inStr;

		try
		{
			inStr = context.getContentResolver().openInputStream(uri);
			numBytesLoaded = inStr != null ? inStr.available() : 0;
			msg = context.getString(R.string.loaded).concat(String.format(" %d Bytes", numBytesLoaded));
			ObjectInputStream oIn = new ObjectInputStream(inStr);
			/* ensure that measurement page is activated
			   to avoid deletion of loaded data afterwards */
			int currService = oIn.readInt();
			/* if data was saved in mode 0, keep current mode */
			if(currService != 0) elm.setService(currService, false);
			/* read in the data */
			ObdProt.PidPvs = (PvList) oIn.readObject();
			ObdProt.VidPvs = (PvList) oIn.readObject();
			ObdProt.tCodes = (PvList) oIn.readObject();
			MainActivity.mPluginPvs = (PvList) oIn.readObject();

			oIn.close();

			log.log(Level.INFO, msg);
			Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
		} catch (Exception ex)
		{
			Toast.makeText(context, ex.toString(), Toast.LENGTH_SHORT).show();
			log.log(Level.SEVERE, uri.toString(), ex);
		}
		return numBytesLoaded;
	}
	// Getter for the PvList
	public PvList getPvs() {
		return (PvList) this.pvs;
	}
}

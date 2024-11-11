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

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.PvList;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Task to save measurements
 *
 * @author Erwin Scheuch-Heilig
 */
public class FileHelper
{
	/** Date Formatter used to generate file name */
	@SuppressLint("SimpleDateFormat")
	private static final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss");
	private static ProgressDialog progress;

	private static final Logger log = Logger.getLogger(FileHelper.class.getName());

	private final Context context;
	private final ElmProt elm;
	/**
	 * Initialize static data for static calls
	 *  @param context APP context
	 *
	 */
	FileHelper(Context context)
	{
		this.context = context;
		this.elm = CommService.elm;
	}

	/**
	 * get default path for load/store operation
	 * * path is based on configured <user data location>/<package name>
	 *
	 * @return default path for current app context
	 */
	static String getPath(Context context)
	{
		// generate file name
		return Environment.getExternalStorageDirectory()
			+ File.separator
			+ context.getPackageName();
	}

	/**
	 * get filename (w/o extension) based on current date & time
	 *
	 * @return file name
	 */
	static String getFileName()
	{
		return dateFmt.format(System.currentTimeMillis());
	}


	/**
	 * Save all data in a independent thread
	 */
	// Control flag for pause/resume
	private boolean isPaused = false;
	// Handler for scheduling the save task at regular intervals
	private Handler handler = new Handler();
	private static boolean isSaving = false; // A flag to track if data is being saved
	// A flag to track if the saving process is paused

	// This method will be triggered by the "Pause" button
	public void pauseSaving() {
		isPaused = true; // Set the flag to true
	}

	// This method will be triggered by the "Resume" button
	public void resumeSaving() {
		isPaused = false; // Set the flag to false
		saveDataThreaded(); // Resume saving data
	}
	/**
	 * Continuously save data every second in a separate thread until paused.
	 */
	void saveDataThreaded() {
		if (isSaving) return;
		isSaving = true;

		final String mPath = getPath(context);
		final String mFileName = mPath + File.separator + getFileName() + ".csv";

		progress = new ProgressDialog(context);
		progress.setMessage(context.getString(R.string.saving_data) + ": " + mFileName);
		progress.setCancelable(false);
		progress.setButton(DialogInterface.BUTTON_NEGATIVE, "Stop", (dialog, which) -> stopSaving());

		progress.show();

		Runnable saveTask = new Runnable() {
			@Override
			public void run() {
				if (!isPaused && isSaving) {
					saveData(mPath, mFileName);
					handler.postDelayed(this, 1000); // Schedule next save in 1 second
				} else {
					progress.dismiss();
				}
			}
		};
		handler.post(saveTask);
	}


	/**
	 * Method to stop the saving process.
	 */
	public void stopSaving() {
		isSaving = false; // Stop the saving process
		handler.removeCallbacksAndMessages(null); // Remove all scheduled tasks
		if (progress != null && progress.isShowing()) {
			progress.dismiss(); // Dismiss the progress dialog
		}
	}
	public void fetchDataForPIDs() {
		// Define PIDs for different parameters (Speed, RPM, etc.)
		String[] pids = {"010D", "010C", "0111", "0104", "010F", "0110", "0902"};

		// Send commands for each PID
		for (String pid : pids) {
			sendPidRequest(pid);
		}
	}

	private void sendPidRequest(String pid) {
		try {
			ElmProt.CMD cmdEnum = ElmProt.CMD.valueOf(pid); // Convert the PID string to CMD enum constant
			String cmd = createCommand(cmdEnum, 0); // Use createCommand to build the full command
			sendCommandForPid(cmd); // Send the constructed command
		} catch (IllegalArgumentException e) {
			log.severe("Invalid PID: " + pid);  // Handle invalid PID if not found in the enum
			Toast.makeText(context, "Invalid PID: " + pid, Toast.LENGTH_SHORT).show();
		}
	}
	// Sends the command to the ELM327 (adapt as needed based on your setup)
	public String sendCommandForPid(String cmd) {
		// This method assumes that you can send raw commands
		elm.sendCommand(ElmProt.CMD.valueOf(cmd), 0);  // CMD.valueOf is an example; replace with how you send commands
		return cmd;
	}

// Assuming you have CMD enum like this
// public enum CMD { RESET("Z", 0, true), ... };

	private String createCommand(ElmProt.CMD cmdID, int param) {
		String cmd = null;

		if (cmdID.isEnabled()) {
			// Get the command from the enum constant directly
			cmd = "01" + cmdID.command; // Use the command from the enum

			// Check if parameter digits are required
			if (cmdID.getParamDigits() > 0) {
				// Format the parameter with leading zeros according to the specified digits
				String fmtString = "%0" + cmdID.getParamDigits() + "X";
				cmd += String.format(fmtString, param);
			}
		}

		return cmd; // Return the final command string
	}

	/*private synchronized void saveData(String mPath, String mFileName) {
		File outFile;

		new File(mPath).mkdirs();
		outFile = new File(mFileName);

		ObdItemAdapter.allowDataUpdates = false;

		try {
			outFile.createNewFile();
			FileOutputStream fStr = new FileOutputStream(outFile);
			ObjectOutputStream oStr = new ObjectOutputStream(fStr);

			// Simulated data instead of actual OBD-II data
			String speed = "Speed response: 50"; // Simulated speed value
			String rpm = "RPM response: 2000"; // Simulated RPM value
			String throttlePosition = "Throttle response: 30"; // Simulated throttle position value
			String engineLoad = "Engine load: 80"; // Simulated engine load
			String intakeTemperature = "Intake temperature: 35"; // Simulated intake temperature
			String maf = "MAF response: 120"; // Simulated MAF value
			String vin = "VIN: 1HGCM82633A123456"; // Simulated VIN

			// Save the data to the file
			oStr.writeObject(speed);
			oStr.writeObject(rpm);
			oStr.writeObject(throttlePosition);
			oStr.writeObject(engineLoad);
			oStr.writeObject(intakeTemperature);
			oStr.writeObject(maf);
			oStr.writeObject(vin);

			oStr.close();
			fStr.close();

			// Log success message
			String msg = String.format("%s %d Bytes to %s", context.getString(R.string.saved), outFile.length(), mPath);
			log.info(msg);
			Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Toast.makeText(context, e.toString(), Toast.LENGTH_SHORT).show();
			e.printStackTrace();
		}

		ObdItemAdapter.allowDataUpdates = true;
	} */


	private synchronized void saveData(String mPath, String mFileName) {
		File outFile;

		new File(mPath).mkdirs();
		outFile = new File(mFileName);

		ObdItemAdapter.allowDataUpdates = false;

		try {
			outFile.createNewFile();
			FileOutputStream fStr = new FileOutputStream(outFile);
			ObjectOutputStream oStr = new ObjectOutputStream(fStr);

			// Fetch and save data for each parameter
			String speed = getData("010D"); // Speed
			String rpm = getData("010C"); // RPM
			String throttlePosition = getData("0111"); // Throttle Position
			String engineLoad = getData("0104"); // Engine Load
			String intakeTemperature = getData("010F"); // Intake Temp
			String maf = getData("0110"); // MAF
			String vin = getData("0902"); // VIN

			// Save the data to the file
			oStr.writeObject(speed);
			oStr.writeObject(rpm);
			oStr.writeObject(throttlePosition);
			oStr.writeObject(engineLoad);
			oStr.writeObject(intakeTemperature);
			oStr.writeObject(maf);
			oStr.writeObject(vin);

			oStr.close();
			fStr.close();

			// Log success message
			String msg = String.format("%s %d Bytes to %s", context.getString(R.string.saved), outFile.length(), mPath);
			log.info(msg);
			Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Toast.makeText(context, e.toString(), Toast.LENGTH_SHORT).show();
			e.printStackTrace();
		}

		ObdItemAdapter.allowDataUpdates = true;
	}


	// Helper method to fetch data for a given PID
	private String getData(String pid) {
		// Send the command for the PID and return the response
		sendPidRequest(pid);

		// You will need to implement response processing here
		return processResponse(pid);  // processResponse needs to handle the response parsing
	}

	private String processResponse(String pid) {
		// Parse the response and return the data as needed
		return "parsed_data";  // Placeholder for actual response handling
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
}

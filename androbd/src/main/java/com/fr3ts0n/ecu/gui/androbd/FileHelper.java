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
import android.util.Log;
import android.widget.Toast;

import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.IndexedProcessVar;
import com.fr3ts0n.pvs.PvList;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import com.fr3ts0n.pvs.PvList;

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
	public transient PvList pvs;
	/**
	 * Initialize static data for static calls
	 *  @param context APP context
	 *
	 */
    public FileHelper(Context context)
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
	static String getPath(Context context) {
		// Use the app's external files directory, which is automatically in /storage/emulated/0/Android/data/
		File dir = context.getExternalFilesDir(null);
		return (dir != null) ? dir.getAbsolutePath() : context.getFilesDir().getAbsolutePath();
	}

	public void setPvs(PvList pvs) {
		this.pvs = pvs;
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

		final String mPath = getPath(context);  // Now this gives the correct app-specific directory
		final String mFileName = getFileName() + ".csv"; // Just use the filename without the full path here

		progress = new ProgressDialog(context);
		progress.setMessage(context.getString(R.string.saving_data) + ": " + mFileName);
		progress.setCancelable(false);
		progress.setButton(DialogInterface.BUTTON_NEGATIVE, "Stop", (dialog, which) -> stopSaving());

		progress.show();

		Runnable saveTask = new Runnable() {
			@Override
			public void run() {
				if (!isPaused && isSaving) {
					saveData(mFileName); // Pass only the path and filename separately
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

	public void saveData(String fileName) {
		// Get the app's internal storage "Documents" directory
		File documentsDir = new File(Environment.getExternalStorageDirectory(), "Documents");

		// Create the directory if it does not exist
		if (!documentsDir.exists()) {
			documentsDir.mkdirs();  // Make sure the "Documents" folder exists
		}

		// Create the full file path in the Documents directory
		String fullPath = documentsDir.getAbsolutePath() + File.separator + fileName;


		// Open CSV file for writing (append mode)
		try (FileWriter writer = new FileWriter(fullPath, true)) {
			// Check if the file is empty to write a header row
			File file = new File(fullPath);
			if (file.length() == 0) {
				writer.write("Description,Value,Units,PID\n"); // Header for CSV
			}

			// Loop through all process variables in `pvs`
			if (pvs == null) {
				Log.e("SaveData", "pvs is null. No data to save.");
				return;
			}

			for (Object obj : pvs.values()) {
				// Ensure the object is of the correct type
				if (obj instanceof IndexedProcessVar) {
					IndexedProcessVar pv = (IndexedProcessVar) obj;

					// Retrieve fields for each data item
					String description = String.valueOf(pv.get(EcuDataPv.FID_DESCRIPT));
					String value = String.valueOf(pv.get(EcuDataPv.FID_VALUE));
					String units = String.valueOf(pv.get(EcuDataPv.FID_UNITS));
					String pid = String.valueOf(pv.get(EcuDataPv.FID_PID));

					// Construct CSV row and write it to the file
					String csvRow = String.format("%s,%s,%s,%s\n", description, value, units, pid);
					writer.write(csvRow);
				} else {
					Log.e("SaveData", "Unexpected object in pvs: " + obj);
				}
			}

			// Flush and close the writer
			writer.flush();
			Log.d("SaveData", "Data saved successfully at: " + fullPath);
			Toast.makeText(context, "Data saved at: " + fullPath, Toast.LENGTH_SHORT).show();

		} catch (IOException e) {
			Log.e("SaveData", "Error saving data", e);
			Toast.makeText(context, "File write error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			// Catch any unexpected exceptions
			Log.e("SaveData", "Unexpected error", e);
			Toast.makeText(context, "Unexpected error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}
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

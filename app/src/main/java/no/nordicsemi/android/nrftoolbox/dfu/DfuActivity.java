/*
 * Copyright (c) 2015, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package no.nordicsemi.android.nrftoolbox.dfu;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import no.nordicsemi.android.error.GattError;
import no.nordicsemi.android.nrftoolbox.AppHelpFragment;
import no.nordicsemi.android.nrftoolbox.R;
import no.nordicsemi.android.nrftoolbox.dfu.adapter.FileBrowserAppsAdapter;
import no.nordicsemi.android.nrftoolbox.dfu.fragment.UploadCancelFragment;
import no.nordicsemi.android.nrftoolbox.dfu.fragment.ZipInfoFragment;
import no.nordicsemi.android.nrftoolbox.dfu.settings.SettingsActivity;
import no.nordicsemi.android.nrftoolbox.dfu.settings.SettingsFragment;
import no.nordicsemi.android.nrftoolbox.scanner.ScannerFragment;
import no.nordicsemi.android.nrftoolbox.utility.DebugLogger;

/**
 * DfuActivity is the main DFU activity It implements DFUManagerCallbacks to receive callbacks from DFUManager class It implements
 * DeviceScannerFragment.OnDeviceSelectedListener callback to receive callback when device is selected from scanning dialog The activity supports portrait and
 * landscape orientations
 */
public class DfuActivity extends ActionBarActivity implements LoaderCallbacks<Cursor>, ScannerFragment.OnDeviceSelectedListener,
		UploadCancelFragment.CancelFragmentListener {
	private static final String TAG = "DfuActivity";

	private static final String PREFS_SAMPLES_VERSION = "no.nordicsemi.android.nrftoolbox.dfu.PREFS_SAMPLES_VERSION";
	private static final int CURRENT_SAMPLES_VERSION = 3;

	private static final String PREFS_DEVICE_NAME = "no.nordicsemi.android.nrftoolbox.dfu.PREFS_DEVICE_NAME";
	private static final String PREFS_FILE_NAME = "no.nordicsemi.android.nrftoolbox.dfu.PREFS_FILE_NAME";
	private static final String PREFS_FILE_TYPE = "no.nordicsemi.android.nrftoolbox.dfu.PREFS_FILE_TYPE";
	private static final String PREFS_FILE_SIZE = "no.nordicsemi.android.nrftoolbox.dfu.PREFS_FILE_SIZE";

	private static final String DATA_DEVICE = "device";
	private static final String DATA_FILE_TYPE = "file_type";
	private static final String DATA_FILE_TYPE_TMP = "file_type_tmp";
	private static final String DATA_FILE_PATH = "file_path";
	private static final String DATA_FILE_STREAM = "file_stream";
	private static final String DATA_INIT_FILE_PATH = "init_file_path";
	private static final String DATA_INIT_FILE_STREAM = "init_file_stream";
	private static final String DATA_STATUS = "status";

	private static final String EXTRA_URI = "uri";

	private static final int ENABLE_BT_REQ = 0;
	private static final int SELECT_FILE_REQ = 1;
	private static final int SELECT_INIT_FILE_REQ = 2;

	private TextView mDeviceNameView;
	private TextView mFileNameView;
	private TextView mFileTypeView;
	private TextView mFileSizeView;
	private TextView mFileStatusView;
	private TextView mTextPercentage;
	private TextView mTextUploading;
	private ProgressBar mProgressBar;

	private Button mSelectFileButton, mUploadButton, mConnectButton;

	private BluetoothDevice mSelectedDevice;
	private String mFilePath;
	private Uri mFileStreamUri;
	private String mInitFilePath;
	private Uri mInitFileStreamUri;
	private int mFileType;
	private int mFileTypeTmp; // This value is being used when user is selecting a file not to overwrite the old value (in case he/she will cancel selecting file)
	private boolean mStatusOk;

	private final BroadcastReceiver mDfuUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			// DFU is in progress or an error occurred
			final String action = intent.getAction();

			if (DfuService.BROADCAST_PROGRESS.equals(action)) {
				final int progress = intent.getIntExtra(DfuService.EXTRA_DATA, 0);
				final int currentPart = intent.getIntExtra(DfuService.EXTRA_PART_CURRENT, 1);
				final int totalParts = intent.getIntExtra(DfuService.EXTRA_PARTS_TOTAL, 1);
				updateProgressBar(progress, currentPart, totalParts, false);
			} else if (DfuService.BROADCAST_ERROR.equals(action)) {
				final int error = intent.getIntExtra(DfuService.EXTRA_DATA, 0);
				updateProgressBar(error, 0, 0, true);

				// We have to wait a bit before canceling notification. This is called before DfuService creates the last notification.
				new Handler().postDelayed(new Runnable() {
					@Override
					public void run() {
						// if this activity is still open and upload process was completed, cancel the notification
						final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
						manager.cancel(DfuService.NOTIFICATION_ID);
					}
				}, 200);
			}
		}
	};

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_feature_dfu);
		isBLESupported();
		if (!isBLEEnabled()) {
			showBLEDialog();
		}
		setGUI();

		ensureSamplesExist();

		// restore saved state
		mFileType = DfuService.TYPE_AUTO; // Default
		if (savedInstanceState != null) {
			mFileType = savedInstanceState.getInt(DATA_FILE_TYPE);
			mFileTypeTmp = savedInstanceState.getInt(DATA_FILE_TYPE_TMP);
			mFilePath = savedInstanceState.getString(DATA_FILE_PATH);
			mFileStreamUri = savedInstanceState.getParcelable(DATA_FILE_STREAM);
			mInitFilePath = savedInstanceState.getString(DATA_INIT_FILE_PATH);
			mInitFileStreamUri = savedInstanceState.getParcelable(DATA_INIT_FILE_STREAM);
			mSelectedDevice = savedInstanceState.getParcelable(DATA_DEVICE);
			mStatusOk = mStatusOk || savedInstanceState.getBoolean(DATA_STATUS);
			mUploadButton.setEnabled(mSelectedDevice != null && mStatusOk);
		}
	}

	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(DATA_FILE_TYPE, mFileType);
		outState.putInt(DATA_FILE_TYPE_TMP, mFileTypeTmp);
		outState.putString(DATA_FILE_PATH, mFilePath);
		outState.putParcelable(DATA_FILE_STREAM, mFileStreamUri);
		outState.putString(DATA_INIT_FILE_PATH, mInitFilePath);
		outState.putParcelable(DATA_INIT_FILE_STREAM, mInitFileStreamUri);
		outState.putParcelable(DATA_DEVICE, mSelectedDevice);
		outState.putBoolean(DATA_STATUS, mStatusOk);
	}

	private void setGUI() {
		final ActionBar actionBar = getSupportActionBar();
		actionBar.setDisplayHomeAsUpEnabled(true);

		mDeviceNameView = (TextView) findViewById(R.id.device_name);
		mFileNameView = (TextView) findViewById(R.id.file_name);
		mFileTypeView = (TextView) findViewById(R.id.file_type);
		mFileSizeView = (TextView) findViewById(R.id.file_size);
		mFileStatusView = (TextView) findViewById(R.id.file_status);
		mSelectFileButton = (Button) findViewById(R.id.action_select_file);

		mUploadButton = (Button) findViewById(R.id.action_upload);
		mConnectButton = (Button) findViewById(R.id.action_connect);
		mTextPercentage = (TextView) findViewById(R.id.textviewProgress);
		mTextUploading = (TextView) findViewById(R.id.textviewUploading);
		mProgressBar = (ProgressBar) findViewById(R.id.progressbar_file);

		final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (isDfuServiceRunning()) {
			// Restore image file information
			mDeviceNameView.setText(preferences.getString(PREFS_DEVICE_NAME, ""));
			mFileNameView.setText(preferences.getString(PREFS_FILE_NAME, ""));
			mFileTypeView.setText(preferences.getString(PREFS_FILE_TYPE, ""));
			mFileSizeView.setText(preferences.getString(PREFS_FILE_SIZE, ""));
			mFileStatusView.setText(R.string.dfu_file_status_ok);
			mStatusOk = true;
			showProgressBar();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		// We are using LocalBroadcastReceiver instead of normal BroadcastReceiver for optimization purposes
		final LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(this);
		broadcastManager.registerReceiver(mDfuUpdateReceiver, makeDfuUpdateIntentFilter());
	}

	@Override
	protected void onPause() {
		super.onPause();

		final LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(this);
		broadcastManager.unregisterReceiver(mDfuUpdateReceiver);
	}

	private static IntentFilter makeDfuUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(DfuService.BROADCAST_PROGRESS);
		intentFilter.addAction(DfuService.BROADCAST_ERROR);
		intentFilter.addAction(DfuService.BROADCAST_LOG);
		return intentFilter;
	}

	private void isBLESupported() {
		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			showToast(R.string.no_ble);
			finish();
		}
	}

	private boolean isBLEEnabled() {
		final BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		final BluetoothAdapter adapter = manager.getAdapter();
		return adapter != null && adapter.isEnabled();
	}

	private void showBLEDialog() {
		final Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		startActivityForResult(enableIntent, ENABLE_BT_REQ);
	}

	private void showDeviceScanningDialog() {
		final FragmentManager fm = getFragmentManager();
		final ScannerFragment dialog = ScannerFragment.getInstance(DfuActivity.this, null, false); // Device that is advertising directly does not have the GENERAL_DISCOVERABLE nor LIMITED_DISCOVERABLE flag set.
		dialog.show(fm, "scan_fragment");
	}

	private void ensureSamplesExist() {
		final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		final int version = preferences.getInt(PREFS_SAMPLES_VERSION, 0);
		if (version == CURRENT_SAMPLES_VERSION)
			return;

		/*
		 * Copy example HEX files to the external storage. Files will be copied if the DFU Applications folder is missing
		 */
		final File root = new File(Environment.getExternalStorageDirectory(), "Nordic Semiconductor");
		if (!root.exists()) {
			root.mkdir();
		}
		final File board = new File(root, "Board");
		if (!board.exists()) {
			board.mkdir();
		}
		final File nrf6310 = new File(board, "nrf6310");
		if (!nrf6310.exists()) {
			nrf6310.mkdir();
		}
		final File pca10028 = new File(board, "pca10028");
		if (!pca10028.exists()) {
			pca10028.mkdir();
		}

		// Remove old files. Those will be moved to a new folder structure
		new File(root, "ble_app_hrs_s110_v6_0_0.hex").delete();
		new File(root, "ble_app_rscs_s110_v6_0_0.hex").delete();
		new File(root, "ble_app_hrs_s110_v7_0_0.hex").delete();
		new File(root, "ble_app_rscs_s110_v7_0_0.hex").delete();
		new File(root, "blinky_arm_s110_v7_0_0.hex").delete();
		new File(root, "dfu_2_0.bat").delete(); // This file has been migrated to 3.0
		new File(root, "dfu_3_0.bat").delete(); // This file has been migrated to 3.1
		new File(root, "dfu_2_0.sh").delete(); // This file has been migrated to 3.0
		new File(root, "dfu_3_0.sh").delete(); // This file has been migrated to 3.1
		new File(root, "README.txt").delete(); // This file has been modified to match v.3.0+

		boolean oldCopied = false;
		boolean newCopied = false;

		// nrf6310 files
		File f = new File(nrf6310, "ble_app_hrs_s110_v6_0_0.hex");
		if (!f.exists()) {
			copyRawResource(R.raw.ble_app_hrs_s110_v6_0_0, f);
			oldCopied = true;
		}
		f = new File(nrf6310, "ble_app_rscs_s110_v6_0_0.hex");
		if (!f.exists()) {
			copyRawResource(R.raw.ble_app_rscs_s110_v6_0_0, f);
			oldCopied = true;
		}
		f = new File(nrf6310, "ble_app_hrs_s110_v7_0_0.hex");
		if (!f.exists()) {
			copyRawResource(R.raw.ble_app_hrs_s110_v7_0_0, f);
			oldCopied = true;
		}
		f = new File(nrf6310, "ble_app_rscs_s110_v7_0_0.hex");
		if (!f.exists()) {
			copyRawResource(R.raw.ble_app_rscs_s110_v7_0_0, f);
			oldCopied = true;
		}
		f = new File(nrf6310, "blinky_arm_s110_v7_0_0.hex");
		if (!f.exists()) {
			copyRawResource(R.raw.blinky_arm_s110_v7_0_0, f);
			oldCopied = true;
		}
		// PCA10028 files
		f = new File(pca10028, "blinky_s110_v7_1_0.hex");
		if (!f.exists()) {
			copyRawResource(R.raw.blinky_s110_v7_1_0, f);
			oldCopied = true;
		}
		f = new File(pca10028, "blinky_s110_v7_1_0_ext_init.dat");
		if (!f.exists()) {
			copyRawResource(R.raw.blinky_s110_v7_1_0_ext_init, f);
			oldCopied = true;
		}
		f = new File(pca10028, "ble_app_hrs_dfu_s110_v7_1_0.hex");
		if (!f.exists()) {
			copyRawResource(R.raw.ble_app_hrs_dfu_s110_v7_1_0, f);
			oldCopied = true;
		}
		f = new File(pca10028, "ble_app_hrs_dfu_s110_v7_1_0_ext_init.dat");
		if (!f.exists()) {
			copyRawResource(R.raw.ble_app_hrs_dfu_s110_v7_1_0_ext_init, f);
			oldCopied = true;
		}
		f = new File(pca10028, "ble_app_hrs_dfu_s110_v8_0_0.zip");
		if (!f.exists()) {
			copyRawResource(R.raw.ble_app_hrs_dfu_s110_v8_0_0, f);
			newCopied = true;
		}

		if (oldCopied)
			Toast.makeText(this, R.string.dfu_example_files_created, Toast.LENGTH_SHORT).show();
		else if (newCopied)
			Toast.makeText(this, R.string.dfu_example_new_files_created, Toast.LENGTH_SHORT).show();

		// Scripts
		newCopied = false;
		f = new File(root, "dfu_3_1.bat");
		if (!f.exists()) {
			copyRawResource(R.raw.dfu_win_3_1, f);
			newCopied = true;
		}
		f = new File(root, "dfu_3_1.sh");
		if (!f.exists()) {
			copyRawResource(R.raw.dfu_mac_3_1, f);
			newCopied = true;
		}
		f = new File(root, "README.txt");
		if (!f.exists()) {
			copyRawResource(R.raw.readme, f);
		}
		if (newCopied)
			Toast.makeText(this, R.string.dfu_scripts_created, Toast.LENGTH_SHORT).show();

		// Save the current version
		preferences.edit().putInt(PREFS_SAMPLES_VERSION, CURRENT_SAMPLES_VERSION).apply();
	}

	/**
	 * Copies the file from res/raw with given id to given destination file. If dest does not exist it will be created.
	 *
	 * @param rawResId the resource id
	 * @param dest     destination file
	 */
	private void copyRawResource(final int rawResId, final File dest) {
		try {
			final InputStream is = getResources().openRawResource(rawResId);
			final FileOutputStream fos = new FileOutputStream(dest);

			final byte[] buf = new byte[1024];
			int read;
			try {
				while ((read = is.read(buf)) > 0)
					fos.write(buf, 0, read);
			} finally {
				is.close();
				fos.close();
			}
		} catch (final IOException e) {
			DebugLogger.e(TAG, "Error while copying HEX file " + e.toString());
		}
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.settings_and_about, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				onBackPressed();
				break;
			case R.id.action_about:
				final AppHelpFragment fragment = AppHelpFragment.getInstance(R.string.dfu_about_text);
				fragment.show(getFragmentManager(), "help_fragment");
				break;
			case R.id.action_settings:
				final Intent intent = new Intent(this, SettingsActivity.class);
				startActivity(intent);
				break;
		}
		return true;
	}

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		if (resultCode != RESULT_OK)
			return;

		switch (requestCode) {
			case SELECT_FILE_REQ: {
				// clear previous data
				mFileType = mFileTypeTmp;
				mFilePath = null;
				mFileStreamUri = null;

				// and read new one
				final Uri uri = data.getData();
			/*
			 * The URI returned from application may be in 'file' or 'content' schema. 'File' schema allows us to create a File object and read details from if
			 * directly. Data from 'Content' schema must be read by Content Provider. To do that we are using a Loader.
			 */
				if (uri.getScheme().equals("file")) {
					// the direct path to the file has been returned
					final String path = uri.getPath();
					final File file = new File(path);
					mFilePath = path;

					updateFileInfo(file.getName(), file.length(), mFileType);
				} else if (uri.getScheme().equals("content")) {
					// an Uri has been returned
					mFileStreamUri = uri;
					// if application returned Uri for streaming, let's us it. Does it works?
					// FIXME both Uris works with Google Drive app. Why both? What's the difference? How about other apps like DropBox?
					final Bundle extras = data.getExtras();
					if (extras != null && extras.containsKey(Intent.EXTRA_STREAM))
						mFileStreamUri = extras.getParcelable(Intent.EXTRA_STREAM);

					// file name and size must be obtained from Content Provider
					final Bundle bundle = new Bundle();
					bundle.putParcelable(EXTRA_URI, uri);
					getLoaderManager().restartLoader(SELECT_FILE_REQ, bundle, this);
				}
				break;
			}
			case SELECT_INIT_FILE_REQ: {
				mInitFilePath = null;
				mInitFileStreamUri = null;

				// and read new one
				final Uri uri = data.getData();
			/*
			 * The URI returned from application may be in 'file' or 'content' schema. 'File' schema allows us to create a File object and read details from if
			 * directly. Data from 'Content' schema must be read by Content Provider. To do that we are using a Loader.
			 */
				if (uri.getScheme().equals("file")) {
					// the direct path to the file has been returned
					mInitFilePath = uri.getPath();
					mFileStatusView.setText(R.string.dfu_file_status_ok_with_init);
				} else if (uri.getScheme().equals("content")) {
					// an Uri has been returned
					mInitFileStreamUri = uri;
					// if application returned Uri for streaming, let's us it. Does it works?
					// FIXME both Uris works with Google Drive app. Why both? What's the difference? How about other apps like DropBox?
					final Bundle extras = data.getExtras();
					if (extras != null && extras.containsKey(Intent.EXTRA_STREAM))
						mInitFileStreamUri = extras.getParcelable(Intent.EXTRA_STREAM);
					mFileStatusView.setText(R.string.dfu_file_status_ok_with_init);
				}
				break;
			}
			default:
				break;
		}
	}

	@Override
	public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
		final Uri uri = args.getParcelable(EXTRA_URI);
		/*
		 * Some apps, f.e. Google Drive allow to select file that is not on the device. There is no "_data" column handled by that provider. Let's try to obtain
		 * all columns and than check which columns are present.
		 */
		// final String[] projection = new String[] { MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA };
		return new CursorLoader(this, uri, null /* all columns, instead of projection */, null, null, null);
	}

	@Override
	public void onLoaderReset(final Loader<Cursor> loader) {
		mFileNameView.setText(null);
		mFileTypeView.setText(null);
		mFileSizeView.setText(null);
		mFilePath = null;
		mFileStreamUri = null;
		mStatusOk = false;
	}

	@Override
	public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
		if (data != null && data.moveToNext()) {
			/*
			 * Here we have to check the column indexes by name as we have requested for all. The order may be different.
			 */
			final String fileName = data.getString(data.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)/* 0 DISPLAY_NAME */);
			final int fileSize = data.getInt(data.getColumnIndex(MediaStore.MediaColumns.SIZE) /* 1 SIZE */);
			String filePath = null;
			final int dataIndex = data.getColumnIndex(MediaStore.MediaColumns.DATA);
			if (dataIndex != -1)
				filePath = data.getString(dataIndex /* 2 DATA */);
			if (!TextUtils.isEmpty(filePath))
				mFilePath = filePath;

			updateFileInfo(fileName, fileSize, mFileType);
		} else {
			mFileNameView.setText(null);
			mFileTypeView.setText(null);
			mFileSizeView.setText(null);
			mFilePath = null;
			mFileStreamUri = null;
			mFileStatusView.setText(R.string.dfu_file_status_error);
			mStatusOk = false;
		}
	}

	/**
	 * Updates the file information on UI
	 *
	 * @param fileName file name
	 * @param fileSize file length
	 */
	private void updateFileInfo(final String fileName, final long fileSize, final int fileType) {
		mFileNameView.setText(fileName);
		switch (fileType) {
			case DfuService.TYPE_AUTO:
				mFileTypeView.setText(getResources().getStringArray(R.array.dfu_file_type)[0]);
				break;
			case DfuService.TYPE_SOFT_DEVICE:
				mFileTypeView.setText(getResources().getStringArray(R.array.dfu_file_type)[1]);
				break;
			case DfuService.TYPE_BOOTLOADER:
				mFileTypeView.setText(getResources().getStringArray(R.array.dfu_file_type)[2]);
				break;
			case DfuService.TYPE_APPLICATION:
				mFileTypeView.setText(getResources().getStringArray(R.array.dfu_file_type)[3]);
				break;
		}
		mFileSizeView.setText(getString(R.string.dfu_file_size_text, fileSize));
		final String extension = mFileType == DfuService.TYPE_AUTO ? "(?i)ZIP" : "(?i)HEX|BIN"; // (?i) =  case insensitive
		final boolean statusOk = mStatusOk = MimeTypeMap.getFileExtensionFromUrl(fileName).matches(extension);
		mFileStatusView.setText(statusOk ? R.string.dfu_file_status_ok : R.string.dfu_file_status_invalid);
		mUploadButton.setEnabled(mSelectedDevice != null && statusOk);

		// Ask the user for the Init packet file if HEX or BIN files are selected. In case of a ZIP file the Init packets should be included in the ZIP.
		if (statusOk && fileType != DfuService.TYPE_AUTO) {
			new AlertDialog.Builder(this).setTitle(R.string.dfu_file_init_title).setMessage(R.string.dfu_file_init_message)
					.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(final DialogInterface dialog, final int which) {
							mInitFilePath = null;
							mInitFileStreamUri = null;
						}
					}).setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, final int which) {
					final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
					intent.setType(DfuService.MIME_TYPE_OCTET_STREAM);
					intent.addCategory(Intent.CATEGORY_OPENABLE);
					startActivityForResult(intent, SELECT_INIT_FILE_REQ);
				}
			}).show();
		}
	}

	/**
	 * Called when the question mark was pressed
	 *
	 * @param view a button that was pressed
	 */
	public void onSelectFileHelpClicked(final View view) {
		new AlertDialog.Builder(this).setTitle(R.string.dfu_help_title).setMessage(R.string.dfu_help_message).setPositiveButton(R.string.ok, null)
				.show();
	}

	/**
	 * Called when Select File was pressed
	 *
	 * @param view a button that was pressed
	 */
	public void onSelectFileClicked(final View view) {
		mFileTypeTmp = mFileType;
		int index = 0;
		switch (mFileType) {
			case DfuService.TYPE_AUTO:
				index = 0;
				break;
			case DfuService.TYPE_SOFT_DEVICE:
				index = 1;
				break;
			case DfuService.TYPE_BOOTLOADER:
				index = 2;
				break;
			case DfuService.TYPE_APPLICATION:
				index = 3;
				break;
		}
		// Show a dialog with file types
		new AlertDialog.Builder(this).setTitle(R.string.dfu_file_type_title)
				.setSingleChoiceItems(R.array.dfu_file_type, index, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(final DialogInterface dialog, final int which) {
						switch (which) {
							case 0:
								mFileTypeTmp = DfuService.TYPE_AUTO;
								break;
							case 1:
								mFileTypeTmp = DfuService.TYPE_SOFT_DEVICE;
								break;
							case 2:
								mFileTypeTmp = DfuService.TYPE_BOOTLOADER;
								break;
							case 3:
								mFileTypeTmp = DfuService.TYPE_APPLICATION;
								break;
						}
					}
				}).setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(final DialogInterface dialog, final int which) {
				openFileChooser();
			}
		}).setNeutralButton(R.string.dfu_file_info, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(final DialogInterface dialog, final int which) {
				final ZipInfoFragment fragment = new ZipInfoFragment();
				fragment.show(getFragmentManager(), "help_fragment");
			}
		}).setNegativeButton(R.string.cancel, null).show();
	}

	private void openFileChooser() {
		final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType(mFileTypeTmp == DfuService.TYPE_AUTO ? DfuService.MIME_TYPE_ZIP : DfuService.MIME_TYPE_OCTET_STREAM);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		if (intent.resolveActivity(getPackageManager()) != null) {
			// file browser has been found on the device
			startActivityForResult(intent, SELECT_FILE_REQ);
		} else {
			// there is no any file browser app, let's try to download one
			final View customView = getLayoutInflater().inflate(R.layout.app_file_browser, null);
			final ListView appsList = (ListView) customView.findViewById(android.R.id.list);
			appsList.setAdapter(new FileBrowserAppsAdapter(this));
			appsList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
			appsList.setItemChecked(0, true);
			new AlertDialog.Builder(this).setTitle(R.string.dfu_alert_no_filebrowser_title).setView(customView)
					.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(final DialogInterface dialog, final int which) {
							dialog.dismiss();
						}
					}).setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, final int which) {
					final int pos = appsList.getCheckedItemPosition();
					if (pos >= 0) {
						final String query = getResources().getStringArray(R.array.dfu_app_file_browser_action)[pos];
						final Intent storeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(query));
						startActivity(storeIntent);
					}
				}
			}).show();
		}
	}

	/**
	 * Callback of UPDATE/CANCEL button on DfuActivity
	 */
	public void onUploadClicked(final View view) {
		if (isDfuServiceRunning()) {
			showUploadCancelDialog();
			return;
		}

		// Check whether the selected file is a HEX file (we are just checking the extension)
		if (!mStatusOk) {
			Toast.makeText(this, R.string.dfu_file_status_invalid_message, Toast.LENGTH_LONG).show();
			return;
		}

		// Save current state in order to restore it if user quit the Activity
		final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		final SharedPreferences.Editor editor = preferences.edit();
		editor.putString(PREFS_DEVICE_NAME, mSelectedDevice.getName());
		editor.putString(PREFS_FILE_NAME, mFileNameView.getText().toString());
		editor.putString(PREFS_FILE_TYPE, mFileTypeView.getText().toString());
		editor.putString(PREFS_FILE_SIZE, mFileSizeView.getText().toString());
		editor.apply();

		showProgressBar();

		final boolean keepBond = preferences.getBoolean(SettingsFragment.SETTINGS_KEEP_BOND, false);

		final Intent service = new Intent(this, DfuService.class);
		service.putExtra(DfuService.EXTRA_DEVICE_ADDRESS, mSelectedDevice.getAddress());
		service.putExtra(DfuService.EXTRA_DEVICE_NAME, mSelectedDevice.getName());
		service.putExtra(DfuService.EXTRA_FILE_MIME_TYPE, mFileType == DfuService.TYPE_AUTO ? DfuService.MIME_TYPE_ZIP : DfuService.MIME_TYPE_OCTET_STREAM);
		service.putExtra(DfuService.EXTRA_FILE_TYPE, mFileType);
		service.putExtra(DfuService.EXTRA_FILE_PATH, mFilePath);
		service.putExtra(DfuService.EXTRA_FILE_URI, mFileStreamUri);
		service.putExtra(DfuService.EXTRA_INIT_FILE_PATH, mInitFilePath);
		service.putExtra(DfuService.EXTRA_INIT_FILE_URI, mInitFileStreamUri);
		service.putExtra(DfuService.EXTRA_KEEP_BOND, keepBond);
		startService(service);
	}

	private void showUploadCancelDialog() {
		final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
		final Intent pauseAction = new Intent(DfuService.BROADCAST_ACTION);
		pauseAction.putExtra(DfuService.EXTRA_ACTION, DfuService.ACTION_PAUSE);
		manager.sendBroadcast(pauseAction);

		final UploadCancelFragment fragment = UploadCancelFragment.getInstance();
		fragment.show(getFragmentManager(), TAG);
	}

	/**
	 * Callback of CONNECT/DISCONNECT button on DfuActivity
	 */
	public void onConnectClicked(final View view) {
		if (isBLEEnabled()) {
			showDeviceScanningDialog();
		} else {
			showBLEDialog();
		}
	}

	@Override
	public void onDeviceSelected(final BluetoothDevice device, final String name) {
		mSelectedDevice = device;
		mUploadButton.setEnabled(mStatusOk);
		mDeviceNameView.setText(name);
	}

	@Override
	public void onDialogCanceled() {
		// do nothing
	}

	private void updateProgressBar(final int progress, final int part, final int total, final boolean error) {
		switch (progress) {
			case DfuService.PROGRESS_CONNECTING:
				mProgressBar.setIndeterminate(true);
				mTextPercentage.setText(R.string.dfu_status_connecting);
				break;
			case DfuService.PROGRESS_STARTING:
				mProgressBar.setIndeterminate(true);
				mTextPercentage.setText(R.string.dfu_status_starting);
				break;
			case DfuService.PROGRESS_ENABLING_DFU_MODE:
				mProgressBar.setIndeterminate(true);
				mTextPercentage.setText(R.string.dfu_status_switching_to_dfu);
				break;
			case DfuService.PROGRESS_VALIDATING:
				mProgressBar.setIndeterminate(true);
				mTextPercentage.setText(R.string.dfu_status_validating);
				break;
			case DfuService.PROGRESS_DISCONNECTING:
				mProgressBar.setIndeterminate(true);
				mTextPercentage.setText(R.string.dfu_status_disconnecting);
				break;
			case DfuService.PROGRESS_COMPLETED:
				mTextPercentage.setText(R.string.dfu_status_completed);
				// let's wait a bit until we cancel the notification. When canceled immediately it will be recreated by service again.
				new Handler().postDelayed(new Runnable() {
					@Override
					public void run() {
						onTransferCompleted();

						// if this activity is still open and upload process was completed, cancel the notification
						final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
						manager.cancel(DfuService.NOTIFICATION_ID);
					}
				}, 200);
				break;
			case DfuService.PROGRESS_ABORTED:
				mTextPercentage.setText(R.string.dfu_status_aborted);
				// let's wait a bit until we cancel the notification. When canceled immediately it will be recreated by service again.
				new Handler().postDelayed(new Runnable() {
					@Override
					public void run() {
						onUploadCanceled();

						// if this activity is still open and upload process was completed, cancel the notification
						final NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
						manager.cancel(DfuService.NOTIFICATION_ID);
					}
				}, 200);
				break;
			default:
				mProgressBar.setIndeterminate(false);
				if (error) {
					showErrorMessage(progress);
				} else {
					mProgressBar.setProgress(progress);
					mTextPercentage.setText(getString(R.string.progress, progress));
					if (total > 1)
						mTextUploading.setText(getString(R.string.dfu_status_uploading_part, part, total));
					else
						mTextUploading.setText(R.string.dfu_status_uploading);
				}
				break;
		}
	}

	private void showProgressBar() {
		mProgressBar.setVisibility(View.VISIBLE);
		mTextPercentage.setVisibility(View.VISIBLE);
		mTextPercentage.setText(null);
		mTextUploading.setText(R.string.dfu_status_uploading);
		mTextUploading.setVisibility(View.VISIBLE);
		mConnectButton.setEnabled(false);
		mSelectFileButton.setEnabled(false);
		mUploadButton.setEnabled(true);
		mUploadButton.setText(R.string.dfu_action_upload_cancel);
	}

	private void onTransferCompleted() {
		clearUI(true);
		showToast(R.string.dfu_success);
	}

	public void onUploadCanceled() {
		clearUI(false);
		showToast(R.string.dfu_aborted);
	}

	@Override
	public void onCancelUpload() {
		mProgressBar.setIndeterminate(true);
		mTextUploading.setText(R.string.dfu_status_aborting);
		mTextPercentage.setText(null);
	}

	private void showErrorMessage(final int code) {
		clearUI(false);
		showToast("Upload failed: " + GattError.parse(code) + " (" + (code & ~(DfuService.ERROR_MASK | DfuService.ERROR_REMOTE_MASK)) + ")");
	}

	private void clearUI(final boolean clearDevice) {
		mProgressBar.setVisibility(View.INVISIBLE);
		mTextPercentage.setVisibility(View.INVISIBLE);
		mTextUploading.setVisibility(View.INVISIBLE);
		mConnectButton.setEnabled(true);
		mSelectFileButton.setEnabled(true);
		mUploadButton.setEnabled(false);
		mUploadButton.setText(R.string.dfu_action_upload);
		if (clearDevice) {
			mSelectedDevice = null;
			mDeviceNameView.setText(R.string.dfu_default_name);
		}
		// Application may have lost the right to these files if Activity was closed during upload (grant uri permission). Clear file related values.
		mFileNameView.setText(null);
		mFileTypeView.setText(null);
		mFileSizeView.setText(null);
		mFileStatusView.setText(R.string.dfu_file_status_no_file);
		mFilePath = null;
		mFileStreamUri = null;
		mInitFilePath = null;
		mInitFileStreamUri = null;
		mStatusOk = false;
	}

	private void showToast(final int messageResId) {
		Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show();
	}

	private void showToast(final String message) {
		Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
	}

	private boolean isDfuServiceRunning() {
		ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
			if (DfuService.class.getName().equals(service.service.getClassName())) {
				return true;
			}
		}
		return false;
	}
}

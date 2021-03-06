package org.madn3s.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.madn3s.controller.Consts.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.madn3s.controller.components.NXTTalker;
import org.madn3s.controller.fragments.ScannerFragment;
import org.madn3s.controller.fragments.SettingsFragment;
import org.madn3s.controller.io.BraveHeartMidgetService;
import org.madn3s.controller.io.HiddenMidgetReader;
import org.madn3s.controller.io.UniversalComms;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by inaki on 1/11/14.
 * This is the extension of the Application class
 */
public class MADN3SController extends Application {
	private static final String tag = MADN3SController.class.getSimpleName();
	public static Context appContext;
	public static final String MODEL_MESSAGE = "MODEL";
	public static final String SERVICE_NAME = "MADN3S";
	public static final UUID APP_UUID = UUID
			.fromString("65da7fe0-8b80-11e3-baa8-0800200c9a66");

	public static final String defaultJSONObjectString = "{}";
	public static final String defaultJSONArrayString = "[]";

	public static final int MEDIA_TYPE_IMAGE = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;
    public static final int MEDIA_TYPE_JSON = 3;
    public static final int MEDIA_TYPE_VTU = 4;

    private static File appDirectory;

	public static SharedPreferences sharedPreferences;
	public static Editor sharedPreferencesEditor;
	public static BluetoothDevice nxt;
	public static BluetoothDevice rightCamera;
	public static BluetoothDevice leftCamera;

	private Handler mBluetoothHandler;
	private Handler.Callback mBluetoothHandlerCallback = null;

	public static WeakReference<BluetoothSocket> rightCameraWeakReference = null;
	public static WeakReference<BluetoothSocket> leftCameraWeakReference = null;

	public static AtomicBoolean isPictureTaken;
	public static AtomicBoolean isRunning;

	public static AtomicBoolean readRightCamera;
	public static AtomicBoolean readLeftCamera;

	public static NXTTalker talker;
	public static boolean isOpenCvLoaded;

    public static JSONArray applyTransform(Mat icpMatrix, JSONArray result, ArrayList<JSONObject> pointsList) {
        Mat pAux;

        try {
            JSONObject point;

            for (int i = 0; i < result.length(); ++i) {
                pAux = getMatFromJsonObject(result.getJSONObject(i));
                Core.multiply(icpMatrix, pAux, pAux);
                point = new JSONObject();
                point.put("x", pAux.get(0, 0)[0]);
                point.put("y", pAux.get(1, 0)[0]);
                point.put("z", pAux.get(2, 0)[0]);

                result.put(i, point);
                pointsList.add(point);
            }

        } catch (Exception e){
            Log.d(tag, "applyTransform. Error procesando punto");
            e.printStackTrace();
        }

        return result;
    }

    public static enum Mode {
		SCANNER("SCANNER", 0), CONTROLLER("CONTROLLER", 1), SCAN("SCAN", 2);

		private String strVal;
		private int intVal;

		Mode(String strVal, int intVal) {
			this.strVal = strVal;
			this.intVal = intVal;
		}

		public int getValue() {
			return intVal;
		}

		@Override
		public String toString() {
			return this.strVal;
		}
	}

	public static enum Device {
		NXT("NXT", 0), RIGHT_CAMERA("RIGHT_CAMERA", 1), LEFT_CAMERA("LEFT_CAMERA", 2);

		private String strVal;
		private int intVal;

		Device(String strVal, int intVal) {
			this.strVal = strVal;
			this.intVal = intVal;
		}

		public int getValue() {
			return intVal;
		}

		@Override
		public String toString() {
			return this.strVal;
		}

		public static Device setDevice(int device) {
			switch (device) {
			case 0:
				return NXT;
			case 1:
				return RIGHT_CAMERA;
			default:
			case 2:
				return LEFT_CAMERA;
			}
		}
	}

	public static enum State {
		CONNECTED(0), CONNECTING(1), FAILED(2);

		private int state;

		State(int state) {
			this.state = state;
		}

		public int getState() {
			return state;
		}

		@Override
		public String toString() {
			return "state: " + this.state;
		}

		public static State setState(int state) {
			switch (state) {
			case 0:
				return CONNECTED;
			case 1:
				return CONNECTING;
			default:
			case 2:
				return FAILED;
			}
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		appContext = super.getBaseContext();
		appDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
				, appContext.getString(R.string.app_name));
		setSharedPreferences();
		setUpBridges();
		Log.d(tag, "onCreate. ");

		MADN3SController.isPictureTaken = new AtomicBoolean(true);
        MADN3SController.isRunning = new AtomicBoolean(true);
        MADN3SController.readRightCamera = new AtomicBoolean(false);
        MADN3SController.readLeftCamera = new AtomicBoolean(false);

		mBluetoothHandler = new Handler() {
			public void handleMessage(Message msg) {
				if (mBluetoothHandlerCallback != null) {
					mBluetoothHandlerCallback.handleMessage(msg);
				}
			}
		};

	}

	/**
	 * Sets up OpenCV Init Callback and all <code>UniversalComms</code> Bridges and Callbacks
	 */
	private void setUpBridges() {

		MainActivity.mLoaderCallback = new BaseLoaderCallback(getBaseContext()) {
		       @Override
		       public void onManagerConnected(int status) {
		           switch (status) {
		               case LoaderCallbackInterface.SUCCESS:
		                   Log.i(tag, "OpenCV loaded successfully");
		                   MADN3SController.isOpenCvLoaded = true;
		                   break;
		               default:
		                   super.onManagerConnected(status);
		                   break;
		           }
		       }
		   };

        BraveHeartMidgetService.sendCalibrationBridge = new UniversalComms() {
            @Override
            public void callback(Object msg) {
                Log.d(tag, "BraveHeartMidgetService.bridge. EXTRA_CALLBACK_SEND");
                Intent williamWallaceIntent = new Intent(getBaseContext(), BraveHeartMidgetService.class);
                williamWallaceIntent.putExtra(EXTRA_CALLBACK_SEND, (String) msg);
                startService(williamWallaceIntent);
            }
        };

		HiddenMidgetReader.bridge = new UniversalComms() {
			@Override
			public void callback(Object msg) {
				Log.d(tag, "HiddenMidgetReader.bridge. EXTRA_CALLBACK_MSG");
				Intent williamWallaceIntent = new Intent(getBaseContext(), BraveHeartMidgetService.class);
				williamWallaceIntent.putExtra(EXTRA_CALLBACK_MSG, (String) msg);
				startService(williamWallaceIntent);
			}
		};

		HiddenMidgetReader.calibrationBridge = new UniversalComms() {
			@Override
			public void callback(Object msg) {
				Log.d(tag, "HiddenMidgetReader.bridge. EXTRA_CALLBACK_CALIBRATION_RESULT");
				Intent williamWallaceIntent = new Intent(getBaseContext(), BraveHeartMidgetService.class);
				williamWallaceIntent.putExtra(EXTRA_CALLBACK_CALIBRATION_RESULT, (String) msg);
				startService(williamWallaceIntent);
			}
		};

		HiddenMidgetReader.pictureBridge = new UniversalComms() {
			@Override
			public void callback(Object msg) {
				Log.d(tag, "HiddenMidgetReader.pictureBridge. EXTRA_CALLBACK_PICTURE");
				Intent williamWallaceIntent = new Intent(getBaseContext(), BraveHeartMidgetService.class);
				williamWallaceIntent.putExtra(EXTRA_CALLBACK_PICTURE, (String) msg);
				startService(williamWallaceIntent);
			}
		};

		ScannerFragment.bridge = new UniversalComms() {
			@Override
			public void callback(Object msg) {
				Log.d(tag, "ScannerFragment.bridge. EXTRA_CALLBACK_SEND");
				Intent williamWallaceIntent = new Intent(getBaseContext(), BraveHeartMidgetService.class);
				williamWallaceIntent.putExtra(EXTRA_CALLBACK_SEND, (String)msg);
				startService(williamWallaceIntent);
			}
		};

		NXTTalker.bridge = new UniversalComms() {
			@Override
			public void callback(Object msg) {
				Log.d(tag, "NXTTalker.bridge. EXTRA_CALLBACK_NXT_MESSAGE");
				Intent williamWallaceIntent = new Intent(getBaseContext(), BraveHeartMidgetService.class);
				williamWallaceIntent.putExtra(EXTRA_CALLBACK_NXT_MESSAGE, (String)msg);
				startService(williamWallaceIntent);
			}
		};

		SettingsFragment.bridge = new UniversalComms() {
			@Override
			public void callback(Object msg) {
				Log.d(tag, "SettingsFragment.bridge. EXTRA_CALLBACK_SEND");
				Intent williamWallaceIntent = new Intent(getBaseContext(), BraveHeartMidgetService.class);
				williamWallaceIntent.putExtra(EXTRA_CALLBACK_SEND, (String)msg);
				startService(williamWallaceIntent);
			}
		};
	}

	/**
	 * Sets SharedPreferences and SharedPreferences Editor for later use with methods defined further
	 */
	private void setSharedPreferences() {
		sharedPreferences = getSharedPreferences(getString(R.string.app_name), MODE_PRIVATE);
		sharedPreferencesEditor = MADN3SController.sharedPreferences.edit();
	}

	public static void clearSharedPreferences() {
		sharedPreferencesEditor.clear().apply();
	}

	public static void removeKeyFromSharedPreferences(String key) {
		sharedPreferencesEditor.remove(key).apply();
	}

	public static void sharedPrefsPutJSONArray(String key, JSONArray value){
		sharedPreferencesEditor.putString(key, value.toString()).apply();
	}

	public static JSONArray sharedPrefsGetJSONArray(String key){
		String jsonString = sharedPreferences.getString(key, defaultJSONArrayString);
		try {
			return new JSONArray(jsonString);
		} catch (Exception e) {
			e.printStackTrace();
			return new JSONArray();
		}
	}

	public static void sharedPrefsPutJSONObject(String key, JSONObject value){
		sharedPreferencesEditor.putString(key, value.toString()).apply();
	}

	public static JSONObject sharedPrefsGetJSONObject(String key){
		String jsonString = sharedPreferences.getString(key, defaultJSONObjectString);
		try {
			return new JSONObject(jsonString);
		} catch (Exception e) {
			e.printStackTrace();
			return new JSONObject();
		}
	}

	public static void sharedPrefsPutString(String key, String value){
		sharedPreferencesEditor.putString(key, value).apply();
	}

	public static String sharedPrefsGetString(String key){
		return sharedPreferences.getString(key, "");
	}

	public static void sharedPrefsPutBoolean(String key, Boolean value){
		sharedPreferencesEditor.putBoolean(key, value).apply();
	}

	public static Boolean sharedPrefsGetBoolean(String key){
		return sharedPreferences.getBoolean(key, false);
	}

	public static void sharedPrefsPutInt(String key, int value){
		sharedPreferencesEditor.putInt(key, value).apply();
	}

	public static int sharedPrefsGetInt(String key){
		return sharedPreferences.getInt(key, 0);
	}

	public static void sharedPrefsPutLong(String key, Long value){
		sharedPreferencesEditor.putLong(key, value).apply();
	}

	public static Long sharedPrefsGetLong(String key){
		return sharedPreferences.getLong(key, (long) 0);
	}

	public static void sharedPrefsPutFloat(String key, Float value){
		sharedPreferencesEditor.putFloat(key, value).apply();
	}

	public static Float sharedPrefsGetFloat(String key){
		return sharedPreferences.getFloat(key, 0);
	}

	public static boolean isToyDevice(BluetoothDevice device) {
		return device.getBluetoothClass() != null
				&& device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.TOY_ROBOT;
	}

	public static boolean isCameraDevice(BluetoothDevice device) {
		return device.getBluetoothClass() != null
				&& (device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.PHONE_SMART || device
						.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.Major.MISC);
	}

	public static boolean isRightCamera(String macAddress) {
        return macAddress != null && rightCamera != null && rightCamera.getAddress() != null && macAddress.equalsIgnoreCase(rightCamera.getAddress());
    }

	public static boolean isLeftCamera(String macAddress) {
        return macAddress != null && leftCamera != null && leftCamera.getAddress() != null && macAddress.equalsIgnoreCase(leftCamera.getAddress());
    }

	public Handler getBluetoothHandler() {
		return mBluetoothHandler;
	}

	public void setBluetoothHandlerCallBack(Handler.Callback callback) {
		this.mBluetoothHandlerCallback = callback;
	}

    /**
     * Crea un Mat desde un JSONObject
     */
    public static Mat getMatFromJsonObject(JSONObject point){
        try {
            double[] data;
            data = new double[3];

            data[0] = point.getDouble("x");
            data[1] = point.getDouble("y");
            data[2] = point.getDouble("z");

            int type = CvType.CV_64F;
            Mat mat = new Mat(4, 1, type);
            mat.put(0, 0, data);
            return mat;

        } catch (Exception e){
            return new Mat();
        }
    }

	/**
	 * Creates a new Mat from a String
	 * @param str Serialized Matrix
	 * @return Mat instance with str matrix values
	 */
	public static Mat getMatFromString(String str, int type){
//		str = "[672.2618351846742, 0, 359.5; 0, 672.2618351846742, 239.5; 0, 0, 1]";
		int rows;
		int cols;
		double[] data;
		String[] colsStr;
		String rowStr;
		String colStr;
		str = str.replaceAll("^\\[|\\]$", "");
		String[] rowsStr = str.split(";");
		rows = rowsStr.length;

		rowStr = rowsStr[0];
		cols = rowStr.split(",").length;
		data = new double[rows*cols];

		for(int row = 0; row < rowsStr.length; ++row){
			rowStr = rowsStr[row];
			colsStr = rowStr.split(",");
			cols = colsStr.length;
			for(int col = 0; col < colsStr.length; ++col){
				colStr = colsStr[col];
				data[row*cols+col] = Double.valueOf(colStr);
			}
		}
		Mat mat = new Mat(rows, cols, type);
		mat.put(0, 0, data);
		return mat;
	}

	public static Mat getImagePointFromString(String str){
		int rows;
		int cols = 1;
		float[] data;
		String[] colsStr;
		String rowStr;
		String colStr;
		str = str.replaceAll("^\\[|\\]$", "");
		String[] rowsStr = str.split(";");
		rows = rowsStr.length;
		int type = CvType.CV_64F;
		Mat mat = new Mat(rows, cols, type);
		mat.create(rows, 1, CvType.CV_32FC2);
		data = new float[2];

		for(int row = 0; row < rowsStr.length; ++row){
			rowStr = rowsStr[row];
			colsStr = rowStr.split(",");
			for(int col = 0; col < colsStr.length; ++col){
				colStr = colsStr[col];
				data[col] = Float.valueOf(colStr);
			}
			mat.put(row, 0, data);
		}
		return mat;
	}

	/**
	 * Returns Public App folder
	 */
	public static File getAppDirectory(){
    	return appDirectory;
    }

    public static JSONObject getInputJson(String filename){
        return getInputJson(sharedPrefsGetString(KEY_PROJECT_NAME), filename);
    }

    public static JSONObject getInputJson(String projectName, String filename){
        String inputString;
        File inputFile = getInputMediaFile(projectName, filename);

        try{
            if(inputFile.exists()){
                BufferedReader inputReader = new BufferedReader(new FileReader(inputFile));
                inputString = inputReader.readLine();
                return new JSONObject(inputString);
            } else {
                Log.e(tag, filename + " doesn't exists");
            }
        } catch (IOException e){
            Log.e(tag, "Error Reading config JSONObject");
            e.printStackTrace();
        } catch (JSONException e){
            Log.e(tag, "Error Parsing config JSONObject");
            e.printStackTrace();
        }

        return new JSONObject();
    }

    public static File getInputMediaFile(String projectName, String filename){
        File projectDirectory = new File(getAppDirectory(), projectName);
        return new File(projectDirectory.getPath(), filename);
    }

    public static File getInputMediaFile(String filename){
        return getInputMediaFile(sharedPrefsGetString(KEY_PROJECT_NAME), filename);
    }

    public static Uri getOutputMediaFileUri(int type, String projectName, String position){
        return Uri.fromFile(getOutputMediaFile(type, projectName, position));
    }

    @SuppressLint("SimpleDateFormat")
    public static File getOutputMediaFile(int type, String projectName, String name){
        return getOutputMediaFile(type, projectName, name, false, true);
    }

    @SuppressLint("SimpleDateFormat")
    public static File getOutputMediaFile(int type, String projectName, String name,
                                          boolean withTimestamp, boolean withIteration){

        Log.d(tag, "getOutputMediaFile. projectName: " + projectName + " name: " + name);
        File mediaStorageDir = new File(getAppDirectory(), projectName);

        if (!mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()){
                Log.d(tag, "getOutputMediaFile. failed to create directory");
                return null;
            }
        }

        if(name == null){
            return null;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        StringBuilder filenameBuilder = new StringBuilder();
        String iteration = String.valueOf(sharedPrefsGetInt(KEY_ITERATION));
        File mediaFile;

        if (type == MEDIA_TYPE_IMAGE){ filenameBuilder.append("IMG_"); }
        if(withIteration){ filenameBuilder.append(iteration).append("_"); }

        filenameBuilder.append(name);

        if(withTimestamp){ filenameBuilder.append("_").append(timeStamp); }
        if(type == MEDIA_TYPE_IMAGE){
            filenameBuilder.append(Consts.IMAGE_EXT);
        } else if(type == MEDIA_TYPE_JSON) {
            filenameBuilder.append(Consts.JSON_EXT);
        } else if(type == MEDIA_TYPE_VTU) {
            filenameBuilder.append(Consts.VTU_EXT);
        }

        mediaFile = new File(mediaStorageDir.getPath(), filenameBuilder.toString());

        return mediaFile;
    }

    public static String saveBitmapAsJpeg(Bitmap bitmap, String name){
        FileOutputStream out;
        try {
            final File imgFile = getOutputMediaFile(MEDIA_TYPE_IMAGE, sharedPrefsGetString(KEY_PROJECT_NAME), name, false, true);

            out = new FileOutputStream(imgFile.getAbsoluteFile());
            bitmap.compress(Consts.BITMAP_COMPRESS_FORMAT, Consts.COMPRESSION_QUALITY, out);

            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(appContext, imgFile.getName(), Toast.LENGTH_SHORT).show();
                }
            });

            return imgFile.getPath();

        } catch (FileNotFoundException e) {
            Log.e(name, "saveBitmapAsJpeg: No se pudo guardar el Bitmap", e);
            return null;
        }
    }

    public static String saveJsonToExternal(String output, String fileName) throws JSONException {
		try {
            String projectName = MADN3SController.sharedPrefsGetString(KEY_PROJECT_NAME);
			File calibrationFile = getOutputMediaFile(MEDIA_TYPE_JSON, projectName, fileName, false, false);
			Log.i(MidgetOfSeville.tag, "saveJsonToExternal. filepath: " + calibrationFile.getAbsolutePath());
			FileOutputStream fos = new FileOutputStream(calibrationFile);
			fos.write(output.getBytes());
			fos.flush();
			fos.close();
			return calibrationFile.getAbsolutePath();
		} catch (FileNotFoundException e) {
			Log.e(tag, "saveJsonToExternal. " + fileName + " FileNotFoundException", e);
		} catch (IOException e) {
			Log.e(tag, "saveJsonToExternal. " + fileName + " IOException", e);
		}

		return null;
	}

    public static String saveBitmapAsPng(Bitmap bitmap, String position){
    	FileOutputStream out;
        try {
            final File imgFile = getOutputMediaFile(MEDIA_TYPE_IMAGE, sharedPrefsGetString(KEY_PROJECT_NAME), position);

            out = new FileOutputStream(imgFile.getAbsoluteFile());
            bitmap.compress(Consts.BITMAP_COMPRESS_FORMAT, Consts.COMPRESSION_QUALITY, out);

            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                	Toast.makeText(appContext, imgFile.getName(), Toast.LENGTH_SHORT).show();
                }
              });

            return imgFile.getPath();

        } catch (FileNotFoundException e) {
            Log.e(position, "saveBitmapAsPng: No se pudo guardar el Bitmap", e);
            return null;
        }
    }

    public static String getMD5EncryptedString(byte[] bytes){
        MessageDigest mdEnc = null;
        try {
            mdEnc = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            System.out.println("Exception while encrypting to md5");
            e.printStackTrace();
        }

        String md5 = null;

        if (mdEnc != null) {
            mdEnc.update(bytes, 0, bytes.length);
            md5 = new BigInteger(1, mdEnc.digest()).toString(16);
            while ( md5.length() < 32 ) {
                md5 = "0"+md5;
            }
        }

        return md5;
    }
}

package com.nazt.android.gpslogger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

import com.nazt.android.gpslogger.service.GPSLoggerService;
import com.nazt.android.gpslogger.R;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class GPSLoggerActivity extends Activity {

	private static final String tag = "GPSLoggerActivity";

	private String currentTripName = "";

	private int altitudeCorrectionMeters = 20;

	private final DecimalFormat sevenSigDigits = new DecimalFormat("0.#######");
	public static final int STATE_READY = 1;
	public static final int STATE_START = 2;
	public static final int STATE_STOP = 3;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		Button button = (Button) findViewById(R.id.ButtonStart);
		button.setOnClickListener(mStartListener);
		button = (Button) findViewById(R.id.ButtonStop);
		button.setOnClickListener(mStopListener);
		setButtonState(STATE_READY);
		// load ข้อมูลชื่อ Trip ในไฟล์ currentTrip.txt ขึ้นมา ถ้าไม่มีสร้างใหม่
		initTripName();

		GPSLoggerService.setShowingDebugToast(true);
	}

	// load ข้อมูลชื่อ Trip ในไฟล์ currentTrip.txt ขึ้นมา ถ้าไม่มีสร้างใหม่
	private void initTripName() {
		// see if there's currently a trip in the trip file
		String tripName = "new";
		try {
			// yyyyMMMddHm
			SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'_'HHmmss");
			tripName = sdf.format(new Date());
		} catch (Exception e) {
			Log.e(tag, e.toString());
		}

		TextView tripNameEditor = (TextView) findViewById(R.id.EditTextTripName);
		tripNameEditor.setText(tripName);
		currentTripName = tripName;
	}

	private OnClickListener mStartListener = new OnClickListener() {
		public void onClick(View v) {
			setButtonState(STATE_START);
			startService(new Intent(GPSLoggerActivity.this,
					GPSLoggerService.class));
		}
	};

	public void setButtonState(int state) {
		Button start_button = (Button) findViewById(R.id.ButtonStart);
		Button stop_button = (Button) findViewById(R.id.ButtonStop);

		start_button.setVisibility(View.GONE);
		stop_button.setVisibility(View.GONE);

		switch (state) {
		case STATE_READY:
		case STATE_STOP:
			start_button.setVisibility(View.VISIBLE);
			break;
		case STATE_START:
			stop_button.setVisibility(View.VISIBLE);
			break;

		default:
			break;
		}
	}

	private OnClickListener mStopListener = new OnClickListener() {
		public void onClick(View v) {
			setButtonState(STATE_STOP);
			doNewTrip();
			stopService(new Intent(GPSLoggerActivity.this,
					GPSLoggerService.class));
		}
	};

	// export ข้อมูลลง KML แล้วลบข้อมูลใน database แล้วก็บันทึกชื่อทริปใหม่ลงใน
	// currentTrip.txt
	private void doNewTrip() {
		SQLiteDatabase db = null;
		try {
			doExport();
			db = openOrCreateDatabase(GPSLoggerService.DATABASE_NAME,
					SQLiteDatabase.OPEN_READWRITE, null);
			db.execSQL("DELETE FROM " + GPSLoggerService.POINTS_TABLE_NAME);
		} catch (Exception e) {
			Log.e(tag, e.toString());
		} finally {
			initTripName();
			close_db(db);
		}
	}

	private void doExport() {
		// export the db contents to a kml file
		SQLiteDatabase db = null;
		Cursor cursor = null;
		try {
			// NAzT
			// Manually set altitudeCorectionMeters
			altitudeCorrectionMeters = 20;

			db = openOrCreateDatabase(GPSLoggerService.DATABASE_NAME,
					SQLiteDatabase.OPEN_READWRITE, null);
			cursor = db.rawQuery("SELECT * " + " FROM "
					+ GPSLoggerService.POINTS_TABLE_NAME
					+ " ORDER BY GMTTIMESTAMP ASC", null);
			int gmtTimestampColumnIndex = cursor
					.getColumnIndexOrThrow("GMTTIMESTAMP");
			int latitudeColumnIndex = cursor.getColumnIndexOrThrow("LATITUDE");
			int longitudeColumnIndex = cursor
					.getColumnIndexOrThrow("LONGITUDE");
			int altitudeColumnIndex = cursor.getColumnIndexOrThrow("ALTITUDE");
			int accuracyColumnIndex = cursor.getColumnIndexOrThrow("ACCURACY");
			if (cursor.moveToFirst()) {
				// fileBuf แชร์กันเขียน
				StringBuffer fileBuf = new StringBuffer();
				String beginTimestamp = null;
				String endTimestamp = null;
				String gmtTimestamp = null;
				// initFileBuf เปิด Setting ไฟล์ KML ส่วนบนก่อนเก็บพิกัด
				// (แชร์fileBuf) initValuesMap เอาไว้เซ็ทค่าเฉยๆ
				initFileBuf(fileBuf, initValuesMap());

				// วนลูปเขียนพิกัดลงไฟล์จ้ะ
				do {
					gmtTimestamp = cursor.getString(gmtTimestampColumnIndex);
					if (beginTimestamp == null) {
						beginTimestamp = gmtTimestamp;
					}
					double latitude = cursor.getDouble(latitudeColumnIndex);
					double longitude = cursor.getDouble(longitudeColumnIndex);
					double altitude = cursor.getDouble(altitudeColumnIndex)
							+ altitudeCorrectionMeters;
					double accuracy = cursor.getDouble(accuracyColumnIndex);
					// เขียนข้อมูลพิกัดลงใน fileBuffer (ตัวแปร fileBuf)
					fileBuf.append(sevenSigDigits.format(longitude) + ","
							+ sevenSigDigits.format(latitude) + "," + altitude
							+ "\n");
				} while (cursor.moveToNext());

				endTimestamp = gmtTimestamp;
				// closeFileBuf ปิด Setting ไฟล์ KML ส่วนหลังหลังเก็บพิกัด (แชร์
				// fileBuf)
				closeFileBuf(fileBuf, beginTimestamp, endTimestamp);
				// แปลงร่าง File Buffer เป็น String
				String fileContents = fileBuf.toString();
				Log.d(tag, fileContents);
				// กำหนดปลายทางการเขียนไฟล์
				File sdDir = new File("/sdcard/GPSLogger");
				sdDir.mkdirs();
				File file = new File("/sdcard/GPSLogger/" + currentTripName
						+ ".kml");
				FileWriter sdWriter = new FileWriter(file, false);
				sdWriter.write(fileContents);
				sdWriter.close();
				// R.string.export_completed Predefined in string.xml
				Toast.makeText(getBaseContext(), R.string.export_completed,
						Toast.LENGTH_LONG).show();
				// cursor.moveToFirst() ไม่สำเร็จ
			} else {
				Toast.makeText(
						getBaseContext(),
						"I didn't find any location points in the database, so no KML file was exported.",
						Toast.LENGTH_LONG).show();
			}
		} catch (FileNotFoundException fnfe) {
			Toast.makeText(
					getBaseContext(),
					"Error trying access the SD card.  Make sure your handset is not connected to a computer and the SD card is properly installed",
					Toast.LENGTH_LONG).show();
		} catch (Exception e) {
			Toast.makeText(getBaseContext(),
					"Error trying to export: " + e.getMessage(),
					Toast.LENGTH_LONG).show();
		} finally {
			if (cursor != null && !cursor.isClosed()) {
				cursor.close();
			}
			close_db(db);
		}
	}

	private HashMap initValuesMap() {
		HashMap valuesMap = new HashMap();

		valuesMap.put("FILENAME", currentTripName);
		// use ground settings for the export
		valuesMap.put("EXTRUDE", "0");
		valuesMap.put("TESSELLATE", "1");
		valuesMap.put("ALTITUDEMODE", "Ground");

		return valuesMap;
	}

	private void initFileBuf(StringBuffer fileBuf, HashMap valuesMap) {
		fileBuf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		fileBuf.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
		fileBuf.append("  <Document>\n");
		fileBuf.append("    <name>" + valuesMap.get("FILENAME") + "</name>\n");
		fileBuf.append("    <description>GPSLogger KML export</description>\n");
		fileBuf.append("    <Style id=\"yellowLineGreenPoly\">\n");
		fileBuf.append("      <LineStyle>\n");
		fileBuf.append("        <color>7f00ffff</color>\n");
		fileBuf.append("        <width>4</width>\n");
		fileBuf.append("      </LineStyle>\n");
		fileBuf.append("      <PolyStyle>\n");
		fileBuf.append("        <color>7f00ff00</color>\n");
		fileBuf.append("      </PolyStyle>\n");
		fileBuf.append("    </Style>\n");
		fileBuf.append("    <Placemark>\n");
		fileBuf.append("      <name>Absolute Extruded</name>\n");
		fileBuf.append("      <description>Transparent green wall with yellow points</description>\n");
		fileBuf.append("      <styleUrl>#yellowLineGreenPoly</styleUrl>\n");
		fileBuf.append("      <LineString>\n");
		fileBuf.append("        <extrude>" + valuesMap.get("EXTRUDE")
				+ "</extrude>\n");
		fileBuf.append("        <tessellate>" + valuesMap.get("TESSELLATE")
				+ "</tessellate>\n");
		fileBuf.append("        <altitudeMode>" + valuesMap.get("ALTITUDEMODE")
				+ "</altitudeMode>\n");
		fileBuf.append("        <coordinates>\n");
	}

	private void closeFileBuf(StringBuffer fileBuf, String beginTimestamp,
			String endTimestamp) {
		fileBuf.append("        </coordinates>\n");
		fileBuf.append("     </LineString>\n");
		fileBuf.append("	 <TimeSpan>\n");
		String formattedBeginTimestamp = zuluFormat(beginTimestamp);
		fileBuf.append("		<begin>" + formattedBeginTimestamp + "</begin>\n");
		String formattedEndTimestamp = zuluFormat(endTimestamp);
		fileBuf.append("		<end>" + formattedEndTimestamp + "</end>\n");
		fileBuf.append("	 </TimeSpan>\n");
		fileBuf.append("    </Placemark>\n");
		fileBuf.append("  </Document>\n");
		fileBuf.append("</kml>");
	}

	private String zuluFormat(String beginTimestamp) {
		// turn 20081215135500 into 2008-12-15T13:55:00Z
		StringBuffer buf = new StringBuffer(beginTimestamp);
		buf.insert(4, '-');
		buf.insert(7, '-');
		buf.insert(10, 'T');
		buf.insert(13, ':');
		buf.insert(16, ':');
		buf.append('Z');
		return buf.toString();
	}

	public void setAltitudeCorrectionMeters(int altitudeCorrectionMeters) {
		this.altitudeCorrectionMeters = altitudeCorrectionMeters;
	}

	public int getAltitudeCorrectionMeters() {
		return altitudeCorrectionMeters;
	}

	public void close_db(SQLiteDatabase db) {
		if (db != null && db.isOpen()) {
			db.close();
		}
	}
}

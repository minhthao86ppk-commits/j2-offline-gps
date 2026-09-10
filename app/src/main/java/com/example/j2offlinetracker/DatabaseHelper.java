package com.example.j2offlinetracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "gps_tracks.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_POINTS = "points";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_LAT = "lat";
    public static final String COLUMN_LNG = "lng";
    public static final String COLUMN_SPEED = "speed";
    public static final String COLUMN_TIME = "time";

    private static final String TABLE_CREATE =
            "CREATE TABLE " + TABLE_POINTS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_LAT + " REAL, " +
                    COLUMN_LNG + " REAL, " +
                    COLUMN_SPEED + " REAL, " +
                    COLUMN_TIME + " INTEGER" +
                    ");";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(TABLE_CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_POINTS);
        onCreate(db);
    }

    public void insertPoint(double lat, double lng, float speed, long time) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_LAT, lat);
        values.put(COLUMN_LNG, lng);
        values.put(COLUMN_SPEED, speed);
        values.put(COLUMN_TIME, time);
        db.insert(TABLE_POINTS, null, values);
    }

    public Cursor getAllPoints() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.query(TABLE_POINTS, null, null, null, null, null, COLUMN_ID + " ASC");
    }

    public int getPointCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_POINTS, null);
        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
        }
        return count;
    }

    public void clearAllPoints() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_POINTS, null, null);
    }
}

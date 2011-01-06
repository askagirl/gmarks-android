package org.thomnichols.android.gmarks;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.cookie.Cookie;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.thomnichols.android.gmarks.thirdparty.ArrayUtils;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.LiveFolders;
import android.text.TextUtils;
import android.util.Log;

public class GmarksProvider extends ContentProvider {

	static final String TAG = "BOOKMARKS PROVIDER";
	static String DB_NAME = "gmarks_sync.db";
	static String COOKIES_TABLE_NAME = "auth_cookies";
	static String BOOKMARKS_TABLE_NAME = "bookmarks";
	static String LABELS_TABLE_NAME = "labels";
	static String BOOKMARK_LABELS_TABLE_NAME = "bookmark_labels";
	
    private static Map<String, String> bookmarksProjectionMap;
    private static Map<String, String> labelsProjectionMap;
    private static Map<String, String> sLiveFolderProjectionMap;

    private static final int BOOKMARKS_URI = 1;
    private static final int BOOKMARK_ID_URI = 2;
    private static final int BOOKMARK_SEARCH_URI = 6;
    private static final int LABELS_URI = 3;
//    private static final int LABELS_ID_URI = 4;
    private static final int LIVE_FOLDER_BOOKMARKS_URI = 5;

    private static final UriMatcher sUriMatcher;

    private DatabaseHelper dbHelper = null;
	
    @Override
    public boolean onCreate() {
    	dbHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, 
    		String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        switch (sUriMatcher.match(uri)) {
        case BOOKMARKS_URI:
            qb.setTables(BOOKMARKS_TABLE_NAME);
            qb.setProjectionMap(bookmarksProjectionMap);
            
            String labelID = uri.getQueryParameter("label_id");
            if ( labelID != null ) {
                qb.setTables("bookmarks join bookmark_labels on bookmarks._id = bookmark_labels.bookmark_id");
                qb.appendWhere("bookmark_labels.label_id=?");
                selectionArgs = (String[])ArrayUtils.addAll(selectionArgs, new String[]{labelID});
            }
            break;

        case BOOKMARK_SEARCH_URI:
            String query = uri.getQueryParameter("q");
            if ( query != null ) {
            	qb.setTables("bookmarks join bookmarks_FTS on bookmarks._id = bookmarks_FTS.docid");
            	qb.appendWhere("bookmarks_FTS MATCH ?");
                selectionArgs = (String[])ArrayUtils.addAll(selectionArgs, new String[]{query});
            }
            else if ( selectionArgs == null || selectionArgs.length < 1 )
            	throw new IllegalArgumentException("No search criteria given for query!");
            break;
            
        case BOOKMARK_ID_URI:
            qb.setTables(BOOKMARKS_TABLE_NAME);
            qb.setProjectionMap(bookmarksProjectionMap);
            qb.appendWhere(Bookmark.Columns._ID + "=" + uri.getPathSegments().get(1));
            break;

        case LABELS_URI:
            qb.setTables(LABELS_TABLE_NAME);
            qb.setProjectionMap(labelsProjectionMap);
            break;

        case LIVE_FOLDER_BOOKMARKS_URI:
            qb.setTables(BOOKMARKS_TABLE_NAME);
            qb.setProjectionMap(sLiveFolderProjectionMap);
            sortOrder = "modified DESC"; // for some reason this gets set to 'name ASC'
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // If no sort order is specified use the default
        String orderBy;
        if (TextUtils.isEmpty(sortOrder)) {
            orderBy = Bookmark.Columns.DEFAULT_SORT_ORDER;
        } else {
            orderBy = sortOrder;
        }

        // Get the database and run the query
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy);

        // Tell the cursor what uri to watch, so it knows when its source data changes
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        // Validate the requested uri
        if (sUriMatcher.match(uri) != BOOKMARKS_URI) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        ContentValues values;
        if (initialValues != null) {
            values = new ContentValues(initialValues);
        } else {
            values = new ContentValues();
        }

        Long now = Long.valueOf(System.currentTimeMillis());

        // Make sure that the fields are all set
        if (values.containsKey(Bookmark.Columns.CREATED_DATE) == false) {
            values.put(Bookmark.Columns.CREATED_DATE, now);
        }

        if (values.containsKey(Bookmark.Columns.MODIFIED_DATE) == false) {
            values.put(Bookmark.Columns.MODIFIED_DATE, now);
        }

        if (values.containsKey(Bookmark.Columns.TITLE) == false) {
            Resources r = Resources.getSystem();
            values.put(Bookmark.Columns.TITLE, r.getString(android.R.string.untitled));
        }

        if (values.containsKey(Bookmark.Columns.DESCRIPTION) == false) {
            values.put(Bookmark.Columns.DESCRIPTION, "");
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long rowId = db.insert(BOOKMARKS_TABLE_NAME, Bookmark.Columns.DESCRIPTION, values);
        if (rowId > 0) {
            Uri noteUri = ContentUris.withAppendedId(Bookmark.CONTENT_URI, rowId);
            getContext().getContentResolver().notifyChange(noteUri, null);
            return noteUri;
        }

        throw new SQLException("Failed to insert row into " + uri);
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int count;
        switch (sUriMatcher.match(uri)) {
        case BOOKMARKS_URI:
            count = db.delete(BOOKMARKS_TABLE_NAME, where, whereArgs);
            break;

        case BOOKMARK_ID_URI:
            String noteId = uri.getPathSegments().get(1);
            count = db.delete(BOOKMARKS_TABLE_NAME, Bookmark.Columns._ID + "=" + noteId
                    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""), whereArgs);
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int count;
        switch (sUriMatcher.match(uri)) {
        case BOOKMARKS_URI:
            count = db.update(BOOKMARKS_TABLE_NAME, values, where, whereArgs);
            break;

        case BOOKMARK_ID_URI:
            String noteId = uri.getPathSegments().get(1);
            count = db.update(BOOKMARKS_TABLE_NAME, values, Bookmark.Columns._ID + "=" + noteId
                    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""), whereArgs);
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public String getType(Uri uri) {
        switch (sUriMatcher.match(uri)) {
        case BOOKMARKS_URI:
        case LIVE_FOLDER_BOOKMARKS_URI:
            return Bookmark.CONTENT_TYPE;
        case BOOKMARK_ID_URI:
            return Bookmark.CONTENT_ITEM_TYPE;
        case LABELS_URI:
        	return Label.CONTENT_TYPE;
//        case LABELS_ID_URI:
//        	return Label.CONTENT_ITEM_TYPE;
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(Bookmark.AUTHORITY, "bookmarks", BOOKMARKS_URI);
        sUriMatcher.addURI(Bookmark.AUTHORITY, "bookmarks/search", BOOKMARK_SEARCH_URI);
        sUriMatcher.addURI(Bookmark.AUTHORITY, "bookmarks/#", BOOKMARK_ID_URI);
        sUriMatcher.addURI(Bookmark.AUTHORITY, "labels", LABELS_URI);
//        sUriMatcher.addURI(Bookmark.AUTHORITY, "labels/#", LABELS_ID_URI);
        sUriMatcher.addURI(Bookmark.AUTHORITY, "live_folders/bookmarks", LIVE_FOLDER_BOOKMARKS_URI);

        bookmarksProjectionMap = new HashMap<String, String>();
        bookmarksProjectionMap.put(Bookmark.Columns._ID, Bookmark.Columns._ID);
        bookmarksProjectionMap.put(Bookmark.Columns.GOOGLEID, Bookmark.Columns.GOOGLEID);
        bookmarksProjectionMap.put(Bookmark.Columns.TITLE, Bookmark.Columns.TITLE);
        bookmarksProjectionMap.put(Bookmark.Columns.HOST, Bookmark.Columns.HOST);
        bookmarksProjectionMap.put(Bookmark.Columns.URL, Bookmark.Columns.URL);
        bookmarksProjectionMap.put(Bookmark.Columns.DESCRIPTION, Bookmark.Columns.DESCRIPTION);
        bookmarksProjectionMap.put(Bookmark.Columns.CREATED_DATE, Bookmark.Columns.CREATED_DATE);
        bookmarksProjectionMap.put(Bookmark.Columns.MODIFIED_DATE, Bookmark.Columns.MODIFIED_DATE);
        
        labelsProjectionMap = new HashMap<String, String>();
        labelsProjectionMap.put(Label.Columns._ID, Label.Columns._ID);
        labelsProjectionMap.put(Label.Columns.TITLE, Label.Columns.TITLE);
        labelsProjectionMap.put(Label.Columns.COUNT, Label.Columns.COUNT);
        
        // Support for Live Folders.
        sLiveFolderProjectionMap = new HashMap<String, String>();
        sLiveFolderProjectionMap.put(LiveFolders._ID, Bookmark.Columns._ID + " AS " +
                LiveFolders._ID);
        sLiveFolderProjectionMap.put(LiveFolders.NAME, Bookmark.Columns.TITLE + " AS " +
                LiveFolders.NAME);
        // Add more columns here for more robust Live Folders.
    }
    
	public static class DatabaseHelper extends SQLiteOpenHelper {
		static final int DB_VERSION = 1;
		
		public DatabaseHelper( Context ctx ) {
			super(ctx, DB_NAME, null, 1 );
		}
		
		static final String[] cookieColumns = { 
			"name", "value", "domain", "path", "expires", "secure"
		};
		
		@Override
		public void onCreate(SQLiteDatabase db) {
			db.execSQL("create table " + COOKIES_TABLE_NAME + " ( "
					+ "name varchar(50) not null primary key,"
					+ "value varchar(50) not null,"
					+ "domain varchar(100),"
					+ "path varchar(100),"
					+ "expires long,"
					+ "secure tinyint default 1 )" );
			
			db.execSQL("create table " + BOOKMARKS_TABLE_NAME + " ( "
					+ "_id integer primary key,"
					+ "google_id varchar(50) not null unique,"
					+ "title varchar(50) not null,"
					+ "url varchar(200) not null,"
					+ "host varchar(50) not null,"
					+ "description varchar(150) not null default ''," 
					+ "created long not null,"
					+ "modified long not null )" );

			db.execSQL("create virtual table " + BOOKMARKS_TABLE_NAME + "_FTS "
					+ "USING fts3(title_fts, host_fts, description_fts, labels_fts)" );
			
			db.execSQL("create index idx_" + BOOKMARKS_TABLE_NAME + "_url on "
					+ BOOKMARKS_TABLE_NAME + "(url asc)" );

			db.execSQL( "create table " + LABELS_TABLE_NAME + " ( "
					+ "_id integer primary key,"
					+ "label varchar(30) unique not null,"
					+ "_count int not null default 0 )" );
			
			db.execSQL( "create table " + BOOKMARK_LABELS_TABLE_NAME + " ( "
					+ "label_id integer not null"
					+ " references labels(_id) on delete cascade,"
					+ "bookmark_id integer not null" 
				    + " references bookmarks(_id) on delete cascade )" );
			
			db.execSQL( "create unique index idx_bookmarks_labels_ref on "
					+ BOOKMARK_LABELS_TABLE_NAME + " ( label_id, bookmark_id )" );
		}
	
		@Override
		public void onUpgrade(SQLiteDatabase arg0, int arg1, int arg2) {
			// TODO Nothing to do until we're upgrading from old db.
		}

		
	    private static final String[] bookmarksIDColumns = new String[] {
	    	Bookmark.Columns.GOOGLEID, 
	    	Bookmark.Columns.TITLE, 
	    	Bookmark.Columns._ID };
	    
	    
	    public Bookmark findByURL(String url, SQLiteDatabase db ) {
	    	// Get the database and run the query
	    	boolean closeDB = false;
	    	if ( db == null ) {
	    		db = getReadableDatabase();
	    		closeDB = true;
	    	}
	        try {
		        Cursor c = db.query(BOOKMARKS_TABLE_NAME, bookmarksIDColumns, 
		        		"where url=?", new String[] {url}, null, null, null);

		        try { // lazy for now, only looking @ first row...
		        	if ( ! c.moveToFirst() ) return null;
		        	Bookmark b = new Bookmark(c.getString(0),c.getString(1),url,null,null,-1,-1);
		        	b.set_id(c.getLong(2));
		        	return b;
		        }
		        finally { c.close(); }
	        }
	        finally { if ( closeDB ) db.close(); }
	    }
	    
	    
	    public long insert( Bookmark b, SQLiteDatabase db ) {
	    	boolean closeDB = false;
	    	if ( db == null ) {
	    		db = getWritableDatabase();
	    		closeDB = true;
	    	}
	        try {
	        	ContentValues vals = new ContentValues();
	        	vals.put(Bookmark.Columns.GOOGLEID, b.getGoogleId());
	        	vals.put(Bookmark.Columns.TITLE, b.getTitle());
	        	vals.put(Bookmark.Columns.URL, b.getUrl());
	        	vals.put(Bookmark.Columns.DESCRIPTION, b.getDescription());
	        	vals.put(Bookmark.Columns.HOST, b.getHost());
	        	vals.put(Bookmark.Columns.CREATED_DATE, b.getCreatedDate());
	        	vals.put(Bookmark.Columns.MODIFIED_DATE, b.getModifiedDate());
	        	return db.insertWithOnConflict( BOOKMARKS_TABLE_NAME, "", vals, 
	        			SQLiteDatabase.CONFLICT_IGNORE );
	        }
	        finally { if ( closeDB ) db.close(); }
	    }

	    public long update( Bookmark b, SQLiteDatabase db ) {
	    	boolean closeDB = false;
	    	if ( db == null ) {
	    		db = getWritableDatabase();
	    		closeDB = true;
	    	}
	        try {
	        	ContentValues vals = new ContentValues();
	        	vals.put(Bookmark.Columns.GOOGLEID, b.getGoogleId());
	        	vals.put(Bookmark.Columns.TITLE, b.getTitle());
	        	vals.put(Bookmark.Columns.URL, b.getUrl());
	        	vals.put(Bookmark.Columns.DESCRIPTION, b.getDescription());
	        	vals.put(Bookmark.Columns.HOST, b.getHost());
	        	vals.put(Bookmark.Columns.CREATED_DATE, b.getCreatedDate());
	        	vals.put(Bookmark.Columns.MODIFIED_DATE, b.getModifiedDate());
	        	return db.updateWithOnConflict( BOOKMARKS_TABLE_NAME, vals,
	        			Bookmark.Columns._ID + "=?", new String[] {""+b.get_id()},
	        			SQLiteDatabase.CONFLICT_IGNORE );
	        }
	        finally { if ( closeDB ) db.close(); }
	    }
		
	    public void persistCookies( List<Cookie> cookies ) {
			SQLiteDatabase db = this.getWritableDatabase();
			db.beginTransaction();
			try {
				// flush old auth cookies before persisting new ones.
				db.delete(COOKIES_TABLE_NAME, "", new String[] {} );
				
				for ( Cookie c : cookies ) {
					ContentValues row = new ContentValues();
					row.put("name", c.getName()	);
					row.put("value", c.getValue() );
					row.put("domain", c.getDomain() );
					row.put("path", c.getPath());
					Date expiry = c.getExpiryDate();
					if ( expiry != null ) row.put("expires", expiry.getTime() );
					row.put("secure", c.isSecure() ? 1 : 0 );
					
					db.insert(COOKIES_TABLE_NAME, "", row);
				}
				db.setTransactionSuccessful();
				Log.d(TAG, "Saved cookies to DB");
			}
			catch (Exception ex ) {
				Log.w(TAG, "Error persisting cookies!", ex );
			}
			finally {
				db.endTransaction();
				db.close();
			}
		}
		
		public List<Cookie> restoreCookies() {
			SQLiteDatabase db = this.getReadableDatabase();
			try {
				Cursor cursor = db.query(COOKIES_TABLE_NAME, cookieColumns, 
						null, null, null, null, null );
				List<Cookie> cookies = new ArrayList<Cookie>();
				while ( cursor.moveToNext() ) {
					BasicClientCookie c = new BasicClientCookie(cursor.getString(0), cursor.getString(1));
					c.setDomain(cursor.getString(2));
					c.setPath(cursor.getString(3));
					Long expires = cursor.getLong(4);
					if ( expires != null ) c.setExpiryDate( new Date(expires) );
					c.setSecure( 0 != cursor.getShort(5) );
					cookies.add( c );
				}
				Log.d(TAG, "Restored cookies");
				cursor.close();
				return cookies;
			}
			finally { db.close(); }
		}
		
		public void clearCookies() {
			SQLiteDatabase db = this.getWritableDatabase();
			try { db.delete(COOKIES_TABLE_NAME, "", null ); }
			finally { db.close(); }
		}
		
		

	}
}
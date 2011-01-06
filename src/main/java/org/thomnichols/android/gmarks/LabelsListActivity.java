package org.thomnichols.android.gmarks;

import org.thomnichols.android.gmarks.R;

import android.app.ListActivity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.Toast;

public class LabelsListActivity extends ListActivity {
	static final String TAG = "GMARKS LABELS";
	
    private static final String[] PROJECTION = new String[] {
        Label.Columns._ID, // 0
        Label.Columns.TITLE, // 1
        Label.Columns.COUNT, // 2
    };
    
    private static final int COLUMN_INDEX_TITLE = 1;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        setContentView(R.layout.labels_list_view);
        ((LinearLayout)findViewById(R.id.allListItems)).setOnClickListener(allItemsClicked);
        setTitle(R.string.labels_activity);

        // If no data was given in the intent (because we were started
        // as a MAIN activity), then use our default content provider.
        Intent intent = getIntent();
        if (intent.getData() == null) intent.setData(Label.CONTENT_URI);
        
    	if (Intent.ACTION_PICK.equals(intent.getAction()) ) {
    		// don't show 'all bookmarks' option:
    		findViewById(R.id.allListItems).setVisibility(View.GONE);
    		setTitle(R.string.choose_label);
    	}

        
        // Perform a managed query. The Activity will handle closing and requerying the cursor
        // when needed.
        Log.i(TAG,"Intent URI: " + getIntent().getData() );
        Cursor cursor = managedQuery( getIntent().getData(), PROJECTION, null, null,
                Label.Columns.DEFAULT_SORT_ORDER );
        
        // Used to map labels from the database to views
        SimpleCursorAdapter adapter = new SimpleCursorAdapter(
        		this, R.layout.labels_list_item, cursor,
                new String[] { Label.Columns.TITLE, Label.Columns.COUNT }, 
                new int[] { R.id.title, R.id.count });
        setListAdapter(adapter);
    }
    
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    	if ( requestCode != resultCode ) {
    		Log.w(TAG, "Did not get expected login result code: " + resultCode );
    	}
    };
    
    OnClickListener allItemsClicked = new OnClickListener() {
		public void onClick(View arg0) {
			startActivity(new Intent(Intent.ACTION_VIEW).setType(Bookmark.CONTENT_TYPE));
		}
	};

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
    	String action = getIntent().getAction();
    	String labelText = ((CursorWrapper)l.getItemAtPosition(position))
    		.getString(COLUMN_INDEX_TITLE);
    	
    	if (Intent.ACTION_PICK.equals(action) ) {
            // The caller is waiting for us to return a label selected
        	Intent result = new Intent();
    		result.setData( ContentUris.withAppendedId(getIntent().getData(), id) );
    		result.putExtra("label", labelText); // most often, they want the label text
    		Log.d(TAG, "User selected label: "+ labelText);
    		
            setResult(RESULT_OK, result);
            finish();
        } 
    	else {
        	// user has selected a label for which to show all bookmarks
        	Uri queryUri = Bookmark.CONTENT_URI.buildUpon()
        		.appendQueryParameter("label_id", ""+id)
        		.appendQueryParameter("label", labelText)
        		.build();
        	startActivity(new Intent(Intent.ACTION_VIEW, queryUri));
        }
    	super.onListItemClick(l, v, position, id);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // if picker, don't show UI options!
        if ( Intent.ACTION_PICK.equals(getIntent().getAction()) ) return true;
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.label_list, menu);
        return true;
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
    	// TODO decide whether or not to make login/logout options visible
    	return super.onPrepareOptionsMenu(menu);
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_sync:
        	Log.d(TAG, "Starting sync...");
        	Toast.makeText(this, "Starting sync...", Toast.LENGTH_SHORT).show();
        	new RemoteSyncTask(this).execute();
        	break;
        case R.id.menu_settings:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
	    case R.id.menu_logout:
	    	Log.d(TAG, "Logging out...");
	    	BookmarksQueryService.getInstance().clearAuthCookies();
	    	Toast.makeText(this, "You are now logged out.", Toast.LENGTH_SHORT).show();
	    	break;
		case R.id.menu_login:
			Log.d(TAG, "Logging in...");
			Toast.makeText(this, "Logging in...", Toast.LENGTH_SHORT).show();
			break;
		}
        return super.onOptionsItemSelected(item);
    }
}
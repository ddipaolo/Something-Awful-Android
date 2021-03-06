package com.ferg.awful.service;


import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.DataSetObserver;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.SectionIndexer;

import com.commonsware.cwac.cache.SimpleWebImageCache;
import com.ferg.awful.AwfulUpdateCallback;
import com.ferg.awful.R;
import com.ferg.awful.constants.Constants;
import com.ferg.awful.preferences.AwfulPreferences;
import com.ferg.awful.thread.AwfulDisplayItem.DISPLAY_TYPE;
import com.ferg.awful.thread.AwfulForum;
import com.ferg.awful.thread.AwfulPageCount;
import com.ferg.awful.thread.AwfulPagedItem;
import com.ferg.awful.thread.AwfulPost;
import com.ferg.awful.thread.AwfulThread;
import com.ferg.awful.thumbnail.ThumbnailBus;
import com.ferg.awful.thumbnail.ThumbnailMessage;

public class AwfulServiceConnection extends BroadcastReceiver implements
		ServiceConnection {

	private static final String TAG = "AwfulServiceAdapter";
	private AwfulService mService;
	private boolean boundState;
	private LayoutInflater inf;
	private ArrayList<AwfulListAdapter> fragments;
	private AwfulPreferences mPrefs;

	public AwfulServiceConnection(){
		fragments = new ArrayList<AwfulListAdapter>();
	}

	@Override
	public void onServiceConnected(ComponentName cName, IBinder bind) {
		if(bind != null && bind instanceof AwfulService.AwfulBinder){
			boundState = true;
			Log.e(TAG, "service connected!");
			mService = ((AwfulService.AwfulBinder) bind).getService();
			for(AwfulListAdapter la : fragments){
				la.connected();
			}
	        mPrefs = new AwfulPreferences(mService);
		}
	}

	@Override
	public void onServiceDisconnected(ComponentName arg0) {
		boundState = false;
		mService = null;
		mPrefs.unRegisterListener();
		Log.e(TAG, "service disconnected!");
		for(AwfulListAdapter la : fragments){
			la.disconnected();
		}
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		if(boundState && intent.getAction().equalsIgnoreCase(Constants.DATA_UPDATE_BROADCAST) && intent.hasExtra(Constants.DATA_UPDATE_ID_EXTRA)){
			int id = intent.getIntExtra(Constants.DATA_UPDATE_ID_EXTRA, -1);
			Log.e(TAG, "Broadcast Received: id "+id);
			for(AwfulListAdapter la : fragments){
				if(la.currentId == id){
					la.dataUpdate();
					Log.e(TAG, "Broadcast ack: id "+la.currentId);
				}
			}
		}
	}
	public void connect(Context parent){
		if(mService == null && !boundState){
			Log.e(TAG, "connect()");
			parent.bindService(new Intent(parent, AwfulService.class), this, Context.BIND_AUTO_CREATE);
			parent.registerReceiver(this, new IntentFilter(Constants.DATA_UPDATE_BROADCAST));
			inf = LayoutInflater.from(parent);
		}
	}
	public void disconnect(Context parent){
		if(mService != null && boundState){
			Log.e(TAG, "disconnect()");
			parent.unbindService(this);
			parent.unregisterReceiver(this);
			boundState = false;
			mService = null;
		}
	}
	public void fetchThread(int id, int page){
		if(boundState){
			mService.fetchThread(id, page);
		}
	}
	public void fetchForum(int id, int page){
		if(boundState){
			mService.fetchForum(id, page);
		}
	}
	
	public class ForumListAdapter extends AwfulListAdapter<AwfulForum>{

		public ForumListAdapter(int id, AwfulUpdateCallback frag) {
			super(id, frag);
		}
		@Override
		public void loadPage(boolean refresh){
			if(mService == null || !boundState){
				return;
			}
			state = mService.getForum(currentId);
			if(refresh){
				fetchForum(currentId, currentPage);
			}
			if(mObserver != null){
				mObserver.onChanged();
			}
			mCallback.dataUpdate(refresh);
		}
		
	}
	
	private static final RotateAnimation mLoadingAnimation = 
		new RotateAnimation(
				0f, 360f,
				Animation.RELATIVE_TO_SELF, 0.5f,
				Animation.RELATIVE_TO_SELF, 0.5f);
	static {
		mLoadingAnimation.setInterpolator(new LinearInterpolator());
		mLoadingAnimation.setRepeatCount(Animation.INFINITE);
		mLoadingAnimation.setDuration(700);
	}

	public class ThreadListAdapter extends AwfulListAdapter<AwfulThread>{
		protected boolean lastReadLoaded;
		private Handler imgHandler = new Handler();

		public ThreadListAdapter(int id, AwfulUpdateCallback frag) {
			super(id, frag);
		}
		@Override
		public void connected(){
			super.connected();
			mService.registerForAvatarCache(currentId+"", onCache);
		}
		@Override
		public void disconnected(){
			super.disconnected();
			mService.unregisterForAvatarCache(onCache);
		}
		
		public void loadPage(boolean refresh){
			if(mService == null || !boundState){
				return;
			}
			state = mService.getThread(currentId);
			if(state !=null && !lastReadLoaded){
				Log.e(TAG,"loading lastread id: "+currentId +" page: "+state.getLastReadPage());
				currentPage = state.getLastReadPage();
				lastReadLoaded = true;
			}
			if(refresh){
				fetchThread(currentId, currentPage);
			}
			if(mObserver != null){
				mObserver.onChanged();
			}
			mCallback.dataUpdate(false);
		}
		public int getLastReadPost() {
			if(state == null || state.getLastReadPage() != currentPage){
				return 0;
			}
			return state.getLastReadPost();
		}
		
		public void goToPage(int page){
			if(currentPage < page && state != null){
				if(page >= (state.getTotalCount()/Constants.ITEMS_PER_PAGE+1)){
					state.setUnreadCount(0);
				}else{
					state.setUnreadCount(state.getTotalCount()-(page-1)*Constants.ITEMS_PER_PAGE);
				}
			}
			lastReadLoaded = true;
			super.goToPage(page);
		}

		public void toggleBookmark() {
			if(state == null || !boundState){
				return;
			}
			mService.toggleBookmark(state.getID());
		}

		public void markLastRead(AwfulPost post) {
			if(mService != null && boundState){
				mService.MarkLastRead(post); 
			}
		}
		
		@Override
		public View getView(int ix, View current, ViewGroup parent) {
			View tmp = super.getView(ix, current, parent);
			ImageView image=(ImageView)tmp.findViewById(R.id.avatar);
			if (image!=null){
				if(image.getTag()!=null && mService != null && mPrefs.imagesEnabled) {
					image.setImageResource(android.R.drawable.ic_menu_rotate);
					ThumbnailMessage msg=mService.getAvatarCache().getBus().createMessage(currentId+"");
					image.startAnimation(mLoadingAnimation);
					msg.setImageView(image);
					msg.setUrl(image.getTag().toString());
					try {
						mService.getAvatarCache().notify(msg.getUrl(), msg);
					}
					catch (Throwable t) {
						Log.e(TAG, "Exception trying to fetch image", t);
					}
				}else{
					image.setImageResource(0);
					image.setVisibility(View.GONE);
				}
			}
			return tmp;
		}
		
		private ThumbnailBus.Receiver<ThumbnailMessage> onCache= new ThumbnailBus.Receiver<ThumbnailMessage>() {
			public void onReceive(final ThumbnailMessage message) {
				final ImageView image=message.getImageView();

				imgHandler.post(new Runnable() {
					public void run() {
						if (image.getTag()!=null && mService != null && image.getTag().toString().equals(message.getUrl())) {
							image.setAnimation(null);
							image.setImageDrawable(mService.getAvatarCache().get(message.getUrl()));
						}
					}
				});
			}
		};
		
	}

	public abstract class AwfulListAdapter<T extends AwfulPagedItem> extends BaseAdapter implements SectionIndexer {
		protected int currentId;
		protected int currentPage;
		protected T state;
		protected DataSetObserver mObserver;
		protected AwfulUpdateCallback mCallback;
		protected AwfulPageCount pageCount;
		public AwfulListAdapter(int id, AwfulUpdateCallback frag){
			currentId = id;
			currentPage = 1;
			mCallback = frag;
			if(boundState){
				loadPage(true);
			}
		}
		public void connected() {
			//this exists to allow graceful caching and reconnection.
			Log.e(TAG, "connected(): "+currentId);
			loadPage(true);
		}
		public void disconnected() {
			Log.e(TAG, "disconnected(): "+currentId);
			if(mObserver != null){
				mObserver.onInvalidated();
			}
		}
		public void dataUpdate() {
			loadPage(false);
			if(mObserver != null){
				mObserver.onChanged();
			}
		}
		@Override
		public int getCount() {
			if(state == null || state.getChildrenCount(currentPage) == 0){
				return 1;
			}
			return state.getChildrenCount(currentPage)+(state.isPaged()?1:0);
		}

		@Override
		public Object getItem(int ix) {
			if(state == null || isPageCount(ix) || state.getChildrenCount(currentPage) == 0){
				return null;
			}
			return state.getChild(currentPage, ix);
		}

		@Override
		public long getItemId(int ix) {
			if(state == null || state.getChildrenCount(currentPage) == 0){
				return 0;
			}
			if(isPageCount(ix)){
				return -2;
			}
			return state.getChild(currentPage, ix).getID();
		}

		protected boolean isPageCount(int ix) {
			return (state != null && state.isPaged() && ix == state.getChildrenCount(currentPage));
		}
		
		@Override
		public int getItemViewType(int ix) {
			if(state == null || state.getChildrenCount(currentPage) == 0){
				return 0;
			}
			if(isPageCount(ix)){
				return 3;
			}
			switch(state.getChild(currentPage, ix).getType()){
			case FORUM:
				return 0;
			case THREAD:
				return 1;
			case POST:
				return 2;
			case PAGE_COUNT:
				return 3;
			}
			return 0;
		}
		public DISPLAY_TYPE getItemType(int ix){
			if(state == null || isPageCount(ix) || state.getChildrenCount(currentPage) == 0){
				return DISPLAY_TYPE.PAGE_COUNT;
			}
			return state.getChild(currentPage, ix).getType();
		}

		@Override
		public View getView(int ix, View current, ViewGroup parent) {
			if(state == null || state.getChildrenCount(currentPage) == 0){
				return inf.inflate(R.layout.loading, parent, false);
			}
			if(isPageCount(ix)){
				if(pageCount == null){
					pageCount = new AwfulPageCount(this);
				}
				return pageCount.getView(inf, current, parent, mPrefs);
			}
			return state.getChild(currentPage, ix).getView(inf, current, parent, mPrefs);
		}

		@Override
		public int getViewTypeCount() {
			return 4;
		}

		@Override
		public boolean hasStableIds() {
			return true;
		}

		@Override
		public boolean isEmpty() {
			return false;
		}

		@Override
		public void registerDataSetObserver(DataSetObserver ob) {
			if(mObserver != null){
				Log.e(TAG, "dataSetObserver overidden!");
			}
			Log.e(TAG, "dataSetObserver set!");
			mObserver = ob;
		}

		@Override
		public void unregisterDataSetObserver(DataSetObserver observer) {
			Log.e(TAG, "dataSetObserver unregister!");
			mObserver = null;
		}

		@Override
		public boolean areAllItemsEnabled() {
			return false;
		}

		@Override
		public boolean isEnabled(int ix) {
			if(state == null || isPageCount(ix) || state.getChildrenCount(currentPage) == 0){
				return false;
			}
			return state.getChild(currentPage,ix).isEnabled();
		}
		public String getTitle() {
			if(state == null){
				return "Loading...";
			}
			return state.getTitle();
		}

		public void refresh() {
			loadPage(true);
		}

		public void goToPage(int pageInt) {
			if(pageInt < 1){
				pageInt = 1;
			}
			if(pageInt > getLastPage()){
				pageInt = getLastPage();
			}
			currentPage = pageInt;
			loadPage(true);
		}
		protected abstract void loadPage(boolean refresh);

		public int getPage() {
			return currentPage;
		}

		public int getLastPage() {
			if(state != null){
				return state.getLastPage();
			}
			return 1;
		}

		public AwfulPagedItem getState() {
			return state;
		}
		public int getLastReadPost() {
			return 1;
		}

		// Section Indexer methods

		@Override
		public int getPositionForSection(int section) {
			return section;
		}
		@Override
		public int getSectionForPosition(int position) {
			return position;
		}
		@Override
		public Object[] getSections() {
			int count = getCount();
			String[] ret = new String[count];
			for(int i=0;i<count;i++) {
				ret[i] = Integer.toString(i+1);
			}
			return ret;
		}
		
		
		
	}
	public ThreadListAdapter createThreadAdapter(int id, AwfulUpdateCallback threadDisplayFragment) {
		ThreadListAdapter ad =  new ThreadListAdapter(id, threadDisplayFragment);
		fragments.add(ad);
		return ad;
	}
	public ForumListAdapter createForumAdapter(int id, AwfulUpdateCallback forumDisplayFragment) {
		ForumListAdapter ad =  new ForumListAdapter(id, forumDisplayFragment);
		fragments.add(ad);
		return ad;
	}
}

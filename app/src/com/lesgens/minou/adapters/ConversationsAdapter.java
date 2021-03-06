package com.lesgens.minou.adapters;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.lesgens.minou.R;
import com.lesgens.minou.db.DatabaseHelper;
import com.lesgens.minou.enums.MessageType;
import com.lesgens.minou.models.Message;
import com.lesgens.minou.models.User;
import com.lesgens.minou.utils.Utils;

public class ConversationsAdapter extends ArrayAdapter<String>{
	private Context mContext;
	private LayoutInflater mInflater = null;
	private SimpleDateFormat sdfMessage = new SimpleDateFormat("HH:mm");
	private SimpleDateFormat sdfDaySameWeek = new SimpleDateFormat("EEEE");
	private SimpleDateFormat sdfDaySameYear = new SimpleDateFormat("dd MMM");
	private SimpleDateFormat sdfDayAnotherYear = new SimpleDateFormat("dd MMM yyyy");
	private Date sameWeek;
	private Calendar sameYear;

	private ArrayList<String> usersId;

	public ConversationsAdapter(Context context, ArrayList<String> usersId) {  
		super(context, R.layout.conversations_item, usersId);
		mContext = context;     
		sameYear = Calendar.getInstance();
		sameYear.add(Calendar.DAY_OF_MONTH, -7);
		sameWeek = sameYear.getTime();
		sameYear.add(Calendar.DAY_OF_MONTH, +7);
		sameYear.set(Calendar.DAY_OF_YEAR, 0);
		this.usersId = usersId;     
	}
	
	static class ViewHolder {
	    public TextView name;
	    public ImageView avatar;
	    public TextView lastMessage;
	    public TextView timeLastMessage;
	    public TextView dayLastMessage;
	    public TextView unreadCount;
	  }

	private LayoutInflater getInflater(){
		if(mInflater == null)
			mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

		return mInflater;       
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		View rowView;

		if(convertView == null){ // Only inflating if necessary is great for performance
			rowView = getInflater().inflate(R.layout.conversations_item, parent, false);
			
			ViewHolder holder = new ViewHolder();
			holder.name = (TextView) rowView.findViewById(R.id.name);
			holder.lastMessage = (TextView) rowView.findViewById(R.id.last_message);
			holder.avatar = (ImageView) rowView.findViewById(R.id.avatar);
			holder.timeLastMessage = (TextView) rowView.findViewById(R.id.last_message_time);
			holder.dayLastMessage = (TextView) rowView.findViewById(R.id.last_message_day);
			holder.unreadCount = (TextView) rowView.findViewById(R.id.unread_count);
			rowView.setTag(holder);
		} else{
			rowView = convertView;
		}
		
		String userId = usersId.get(position);
		User user = DatabaseHelper.getInstance().getUser(userId);
		ViewHolder holder = (ViewHolder) rowView.getTag();
		final String channelName = user.getUsername();
		
		holder.name.setText(channelName);
		
		if(user.getAvatar() != null){
			holder.avatar.setImageBitmap(Utils.cropToCircle(user.getAvatar()));
		}
		
		Message lastMessage = DatabaseHelper.getInstance().getLastMessage(user.getNamespace());
		if(lastMessage != null){
			if(lastMessage.getMsgType() == MessageType.TEXT){
				holder.lastMessage.setText(lastMessage.getContent());
			} else if(lastMessage.getMsgType() == MessageType.IMAGE){
				holder.lastMessage.setText(R.string.picture);
			} else if(lastMessage.getMsgType() == MessageType.VIDEO){
				holder.lastMessage.setText(R.string.video);
			} else if(lastMessage.getMsgType() == MessageType.AUDIO){
				holder.lastMessage.setText(R.string.audio);
			}
			holder.timeLastMessage.setText(sdfMessage.format(lastMessage.getTimestamp()));
			holder.dayLastMessage.setText(getTimeText(lastMessage.getTimestamp()));
		}
		
		int unread = DatabaseHelper.getInstance().getUnreadCountForTopic(user.getNamespace());
		if(unread == 0) {
			holder.unreadCount.setVisibility(View.GONE);
		} else {
			holder.unreadCount.setVisibility(View.VISIBLE);
			holder.unreadCount.setText(unread + "");
		}

		return rowView;
	}
	
	public String getTimeText(Timestamp time){
		if(sameWeek.before(time)){
			return sdfDaySameWeek.format(time);
		} else if(sameYear.before(time)){
			return sdfDaySameYear.format(time);
		} else{
			return sdfDayAnotherYear.format(time);
		}
	}

	public ArrayList<String> getItems() {
		return usersId;
	}
	
}

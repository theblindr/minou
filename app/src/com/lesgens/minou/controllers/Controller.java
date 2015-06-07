package com.lesgens.minou.controllers;

import java.util.ArrayList;
import java.util.HashMap;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.checkin.avatargenerator.AvatarGenerator;
import com.facebook.Session;
import com.lesgens.minou.models.City;
import com.lesgens.minou.models.Message;
import com.lesgens.minou.models.User;
import com.lesgens.minou.utils.Utils;

public class Controller {
	private static final String TAG = "Controller";
	private City city;
	private HashMap<String, User> users;
	private Session session;
	private User myselfUser;
	private int dimensionAvatar;
	private HashMap<String, ArrayList<Message>> messages;
	


	private static Controller controller;

	private Controller(){
		city = new City("", null, null);
		users = new HashMap<String, User>();
		messages = new HashMap<String, ArrayList<Message>>();
	}

	public static Controller getInstance(){
		if(controller == null){
			controller = new Controller();
		}

		return controller;
	}

	public void setCity(City city){
		this.city = city;
	}

	public City getCity(){
		return city;
	}

	public void addUser(User user){
		users.put(user.getId(), user);
	}

	public User getUser(String tokenId){
		if(users.get(tokenId) == null){
			users.put(tokenId, new User("user" + tokenId, AvatarGenerator.generate(dimensionAvatar, dimensionAvatar), tokenId));
		}
		return users.get(tokenId);
	}
	
	public User getUser(final String tokenId, final String fakeName){
		if(users.get(tokenId) == null){
			users.put(tokenId, new User(fakeName, AvatarGenerator.generate(dimensionAvatar, dimensionAvatar), tokenId));
		}
		return users.get(tokenId);
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public void setMyOwnUser(User user){
		this.myselfUser = user;
	}

	public User getMyself(){
		return myselfUser;
	}

	public String getMyId(){
		return myselfUser.getId().substring(0, myselfUser.getId().indexOf("."));
	}

	public void setDimensionAvatar(Context context) {
		dimensionAvatar = Utils.dpInPixels(context, 50);
	}

	public int getDimensionAvatar() {
		return dimensionAvatar;
	}
	
	public ArrayList<Message> getMessages(final String channel){
		Log.i(TAG, "getting messages from=" + channel + " messages=" + messages.get(channel));
		return messages.get(channel) != null ? messages.get(channel) : new ArrayList<Message>();
	}
	
	public void addMessage(final String channel, final Message message){
		if(messages.get(channel) != null){
			messages.get(channel).add(message);
		} else{
			ArrayList<Message> newMessage = new ArrayList<Message>();
			newMessage.add(message);
			messages.put(channel, newMessage);
		}
	}

	public void addBlockPerson(Activity activity, String id){
		SharedPreferences sharedPref = activity.getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = sharedPref.edit();
		String blocked = getBlockedPeopleString(activity);
		if(blocked.isEmpty()){
			blocked = id;
		} else{
			blocked += "," + id;
		}
		editor.putString("blockedList", blocked);
		editor.commit();
	}

	private String getBlockedPeopleString(Activity activity){
		SharedPreferences sharedPref = activity.getPreferences(Context.MODE_PRIVATE);
		String blocked = sharedPref.getString("blockedList", "");

		return blocked;

	}

	public ArrayList<String> getBlockedPeople(Activity activity){
		SharedPreferences sharedPref = activity.getPreferences(Context.MODE_PRIVATE);
		String blocked = sharedPref.getString("blockedList", "");

		ArrayList<String> blockedPeople = new ArrayList<String>();
		for(String b : blocked.split(",")){
			blockedPeople.add(b);
		}

		Log.i(TAG, "blockedPeople=" + blockedPeople.toString());
		return blockedPeople;

	}

}

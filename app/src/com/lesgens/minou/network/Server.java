package com.lesgens.minou.network;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;
import ws.wamp.jawampa.PubSubData;
import ws.wamp.jawampa.Reply;
import ws.wamp.jawampa.WampClient;
import ws.wamp.jawampa.WampClientBuilder;
import ws.wamp.jawampa.WampError;
import android.content.Context;
import android.os.AsyncTask;
import android.util.JsonReader;
import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.lesgens.minou.ChatActivity;
import com.lesgens.minou.application.MinouApplication;
import com.lesgens.minou.controllers.Controller;
import com.lesgens.minou.controllers.PreferencesController;
import com.lesgens.minou.db.DatabaseHelper;
import com.lesgens.minou.enums.MessageType;
import com.lesgens.minou.enums.Roles;
import com.lesgens.minou.enums.SendingStatus;
import com.lesgens.minou.listeners.AvatarUploadListener;
import com.lesgens.minou.listeners.CrossbarConnectionListener;
import com.lesgens.minou.listeners.EventsListener;
import com.lesgens.minou.listeners.MinouDownloadAvatarProgressListener;
import com.lesgens.minou.listeners.MinouUploadFileProgressListener;
import com.lesgens.minou.listeners.TrendingChannelsListener;
import com.lesgens.minou.listeners.UserAuthenticatedListener;
import com.lesgens.minou.listeners.UsernameListener;
import com.lesgens.minou.models.Channel;
import com.lesgens.minou.models.ChannelTrending;
import com.lesgens.minou.models.City;
import com.lesgens.minou.models.Message;
import com.lesgens.minou.models.Topic;
import com.lesgens.minou.models.User;
import com.lesgens.minou.network.HTTPRequest.RequestType;
import com.lesgens.minou.utils.AvatarGenerator;
import com.lesgens.minou.utils.NotificationHelper;
import com.lesgens.minou.utils.Utils;

public class Server {

	private static List<UserAuthenticatedListener> userAuthenticatedListeners = new ArrayList<UserAuthenticatedListener>();
	private static ArrayList<EventsListener> eventsListeners = new ArrayList<EventsListener>();
	private static ArrayList<CrossbarConnectionListener> connectionListeners = new ArrayList<CrossbarConnectionListener>();
	private static String address = "https://minou-backend.herokuapp.com/";
	private static String TAG = "Server";
	private static boolean isConnected = false;
	private static WampClient client;
	private static class PokeCrossbarServer extends TimerTask {
		public void run() {
			sendStayAliveMessage();
		}
	}
	private static Timer timer;

	public static void connect(String authenticationToken) {
		AsyncTask<String, Void, String> request = new AsyncTask<String, Void, String>() {

			@Override
			protected String doInBackground(String... arg0) {
				String finalAddress = address + "auth";
				Log.i(TAG, "auth address: " + finalAddress);
				List<NameValuePair> data = new ArrayList<NameValuePair>();
				data.add(new BasicNameValuePair("fb_token", arg0[0]));
				Log.i(TAG, "fb_token: " + arg0[0]);
				HTTPRequest request = new HTTPRequest(finalAddress, RequestType.POST, data);
				return request.getOutput();
			}

			@Override
			protected void onPostExecute(String result) {
				super.onPostExecute(result);
				Log.i(TAG, "Auth response's json: "+ result);
				try {
					readAuth(new StringReader(result));
					for(UserAuthenticatedListener listener: userAuthenticatedListeners) {
						listener.onUserAuthenticated();
					}
				} catch(IOException ioe){
					ioe.printStackTrace();
					for(UserAuthenticatedListener listener: userAuthenticatedListeners) {
						listener.onUserServerErrorAuthentication();
					}
				} catch (Exception e) {
					Log.i(TAG, "Something went wrong while authenticating.");
					e.printStackTrace();
					for(UserAuthenticatedListener listener: userAuthenticatedListeners) {
						listener.onUserNetworkErrorAuthentication();
					}
				}
			}

		};
		request.execute(authenticationToken);
	}    

	public static void connectToCrossbar(final Context context){
		try {
			// Create a builder and configure the client
			disconnect();
			WampClientBuilder builder = new WampClientBuilder();
			builder.withUri("wss://minou-crossbar.herokuapp.com/ws")
			.withRealm("minou")
			.withInfiniteReconnects()
			.withAuthId(Controller.getInstance().getId())
			.withAuthMethod(new WampCraAuthentication())
			.withReconnectInterval(2, TimeUnit.SECONDS);
			// Create a client through the builder. This will not immediatly start
			// a connection attempt
			client = builder.build();
			// Subscribe on the clients status updates
			client.statusChanged()
			.subscribe(new Action1<WampClient.Status>() {
				@Override
				public void call(WampClient.Status t1) {
					Log.i(TAG, "Session status changed to " + t1);

					if (t1 instanceof WampClient.ClientConnected) {		

						initChannels(context);

						subscribeToConversation(context, Controller.getInstance().getMyself());

						subscribeToCity(context, Controller.getInstance().getGeolocation().getStateNameSpace());

						addGeolocationChannels();

						for(String channel : DatabaseHelper.getInstance().getPublicChannels()){
							subscribeToTopic(context, channel);
						}

						for(String userId : DatabaseHelper.getInstance().getConversations()){
							final User user = DatabaseHelper.getInstance().getUser(userId);
							subscribeToConversation(context, user);
						}

						getTopicsCount();
						getUsers(DatabaseHelper.getInstance().getUsersId());

						timer = new Timer();
						timer.schedule(new PokeCrossbarServer(), 0, 35000);

						for(CrossbarConnectionListener listener : connectionListeners){
							listener.onConnected();
						}

						isConnected = true;
					} else if (t1 instanceof WampClient.ClientDisconnected) {
						closeSubscriptions();
						isConnected = false;
					} else if (t1 instanceof WampClient.ClientConnecting) {

					}
				}
			}, new Action1<Throwable>() {
				@Override
				public void call(Throwable t) {
					Log.i(TAG, "Session ended with error " + t);
				}
			}, new Action0() {
				@Override
				public void call() {
					Log.i(TAG, "Session ended normally");
				}
			});

			client.open();

		} catch (WampError e) {
			e.printStackTrace();
			return;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void addGeolocationChannels(){
//		if(!DatabaseHelper.getInstance().isPublicChannelAlreadyIn(Controller.getInstance().getGeolocation().getCountryNameSpace())) {
//			DatabaseHelper.getInstance().addPublicChannel(Controller.getInstance().getGeolocation().getCountryNameSpace());
//		}

		if(!DatabaseHelper.getInstance().isPublicChannelAlreadyIn(Controller.getInstance().getGeolocation().getStateNameSpace())) {
			DatabaseHelper.getInstance().addPublicChannel(Controller.getInstance().getGeolocation().getStateNameSpace());
		}


//		if(!DatabaseHelper.getInstance().isPublicChannelAlreadyIn(Controller.getInstance().getGeolocation().getCityNameSpace())) {
//			DatabaseHelper.getInstance().addPublicChannel(Controller.getInstance().getGeolocation().getCityNameSpace());
//		}

//		if(!DatabaseHelper.getInstance().isPublicChannelAlreadyIn(Controller.getInstance().getGeolocation().getCityNameSpace() + ".general")) {
//			DatabaseHelper.getInstance().addPublicChannel(Controller.getInstance().getGeolocation().getCityNameSpace() + ".general");
//		}

		if(!DatabaseHelper.getInstance().isPublicChannelAlreadyIn(Controller.getInstance().getGeolocation().getStateNameSpace() + ".general")) {
			DatabaseHelper.getInstance().addPublicChannel(Controller.getInstance().getGeolocation().getStateNameSpace() + ".general");
		}

//		if(!DatabaseHelper.getInstance().isPublicChannelAlreadyIn(Controller.getInstance().getGeolocation().getCountryNameSpace() + ".general")) {
//			DatabaseHelper.getInstance().addPublicChannel(Controller.getInstance().getGeolocation().getCountryNameSpace() + ".general");
//		}
	}

	private static void closeSubscriptions() {
		if(Controller.getInstance().getChannelsContainer() != null){
			Controller.getInstance().getChannelsContainer().closeSubscriptions();
		}
	}

	public static void disconnect(){
		if(client != null){
			closeSubscriptions();
			client.close();
		}
	}

	private static void initChannels(final Context context){
		final String channelName = Channel.WORLDWIDE_CHANNEL;		
		Controller.getInstance().initChannelContainer(createChannelCity(context, Channel.BASE_PUBLIC_CHANNEL));
		subscribeToCity(context, channelName);
	}

	public static void subscribeToCity(final Context context, final String channelName){
		if(Controller.getInstance().getChannelsContainer().isContainSubscription(channelName)){
			return;
		}

		final City city = createChannelCity(context, channelName);

		Controller.getInstance().getChannelsContainer().addByForceSubscription(city);
	}

	public static void subscribeToTopic(final Context context, final String channelName){
		if(Controller.getInstance().getChannelsContainer().isContainSubscription(channelName)){
			return;
		}

		final Topic topic = createChannelTopic(context, channelName);

		Controller.getInstance().getChannelsContainer().addSubscription(topic);

		getLastMessages(topic);
	}

	public static void subscribeToConversation(final Context context, final User user){
		if(Controller.getInstance().getChannelsContainer().isContainSubscription(user.getNamespace())){
			return;
		}

		final User userToAdd = createPrivateChannel(context, user);

		Controller.getInstance().getChannelsContainer().addByForceSubscription(userToAdd);

		getLastPrivateMessages(userToAdd);
	}

	private static Topic createChannelTopic(final Context context, final String channelName){
		final String fullChannelName = Utils.getNormalizedString(channelName);
		Log.i(TAG, "Subscribing to: " + fullChannelName);		
		Observable<PubSubData> channelSubscription = client.makeSubscription(fullChannelName);
		final Topic topic = new Topic(fullChannelName, channelSubscription);
		String parentNamespace = fullChannelName.substring(0, fullChannelName.lastIndexOf("."));
		String parentParentNamespace = parentNamespace.substring(0, parentNamespace.lastIndexOf("."));
		topic.setParentName(parentNamespace.substring(parentNamespace.lastIndexOf(".") + 1) + ", " + parentParentNamespace.substring(parentParentNamespace.lastIndexOf(".") + 1));
		channelSubscription.forEach(new Action1<PubSubData>(){

			@Override
			public void call(PubSubData msg) {
				Log.i(TAG, "Received new message=" + msg.keywordArguments());
				String type = msg.keywordArguments().get("content_type").asText();
				final String id = msg.keywordArguments().get("from").asText();
				final User user = DatabaseHelper.getInstance().getUser(id, msg.keywordArguments().get("user_name").asText());

				String content = msg.keywordArguments().get("content").asText();

				Log.i(TAG, "From=" + id + " me=" + Controller.getInstance().getId());
				final boolean isIncoming = !id.equals(Controller.getInstance().getId());
				Message m = new Message(user, content, user.getName(), topic, isIncoming, null, isIncoming ? SendingStatus.RECEIVED : SendingStatus.SENT, MessageType.fromString(type));

				
				boolean isGoodChannel = false;
				DatabaseHelper.getInstance().addMessage(m, user.getId(), channelName);
				if(MinouApplication.getCurrentActivity() instanceof ChatActivity){
					if(topic.getNamespace().equals(((ChatActivity) MinouApplication.getCurrentActivity()).getNamespace())){
						isGoodChannel = true;
						DatabaseHelper.getInstance().updateMessageAsRead(m.getId().toString());
					}
				}
				for(EventsListener el : eventsListeners) {
					el.onNewEvent(m);
				}
				if((!MinouApplication.isActivityVisible() || !isGoodChannel) 
						&& (PreferencesController.isPublicNotificationsEnabled(context, fullChannelName) 
								|| m.getContent().toLowerCase().contains(Controller.getInstance().getMyself().getUsername().toLowerCase()))
								&& isIncoming){
					Log.i(TAG, "Application not visible, should send notification");
					NotificationHelper.notify(context, topic, user, content);
				}
			}}, new Action1<Throwable>() {

				@Override
				public void call(Throwable arg0) {
					Log.i(TAG, "Error on channel, error=" + arg0.getMessage());
				}});



		return topic;
	}

	private static City createChannelCity(final Context context, final String channelName){
		final String fullChannelName = Utils.getNormalizedString(channelName);
		Log.i(TAG, "Subscribing to: " + fullChannelName);
		Observable<PubSubData> channelSubscription = client.makeSubscription(fullChannelName);
		final City city = new City(fullChannelName, channelSubscription);
		channelSubscription.forEach(new Action1<PubSubData>(){

			@Override
			public void call(PubSubData msg) {
				Log.i(TAG, "Received new message " + msg.keywordArguments());
				String type = msg.keywordArguments().get("content_type").asText();
				final String id = msg.keywordArguments().get("from").asText();
				final User user = DatabaseHelper.getInstance().getUser(id, msg.keywordArguments().get("user_name").asText());
				String content = msg.keywordArguments().get("content").asText();

				Log.i(TAG, "From=" + id + " me=" + Controller.getInstance().getId());

				final boolean isIncoming = !id.equals(Controller.getInstance().getId());
				Message m = new Message(user, content, user.getName(), city, isIncoming, null, isIncoming ? SendingStatus.RECEIVED : SendingStatus.SENT, MessageType.fromString(type));
				
				boolean isGoodChannel = false;
				DatabaseHelper.getInstance().addMessage(m, user.getId(), channelName);
				if(MinouApplication.getCurrentActivity() instanceof ChatActivity){
					if(city.getNamespace().equals(((ChatActivity) MinouApplication.getCurrentActivity()).getNamespace())){
						isGoodChannel = true;
						DatabaseHelper.getInstance().updateMessageAsRead(m.getId().toString());
					}
				}
				for(EventsListener el : eventsListeners) {
					el.onNewEvent(m);
				}
				if((!MinouApplication.isActivityVisible() || !isGoodChannel) 
						&& (PreferencesController.isPublicNotificationsEnabled(context, fullChannelName) 
								|| m.getContent().toLowerCase().contains(Controller.getInstance().getMyself().getUsername().toLowerCase()))
								&& isIncoming){
					Log.i(TAG, "Application not visible, should send notification");
					NotificationHelper.notify(context, city, user, content);
				}
			}}, new Action1<Throwable>() {

				@Override
				public void call(Throwable arg0) {
					Log.i(TAG, "Error on channel city, error=" + arg0.getMessage());
				}});

		return city;
	}

	private static User createPrivateChannel(final Context context, final User userToCreate){
		final String fullChannelName = Utils.getNormalizedString(Channel.BASE_CHANNEL + userToCreate.getId().replace(".", "_").replace("-", "_"));
		Log.i(TAG, "Subscribing to: " + fullChannelName);
		Observable<PubSubData> channelSubscription = client.makeSubscription(fullChannelName);
		channelSubscription.forEach(new Action1<PubSubData>(){

			@Override
			public void call(PubSubData msg) {
				Log.i(TAG, "Received new private message " + msg.keywordArguments());
				String type = msg.keywordArguments().get("content_type").asText();
				final String id = msg.keywordArguments().get("from").asText();
				final User user = DatabaseHelper.getInstance().getUser(id, msg.keywordArguments().get("user_name").asText());
				subscribeToConversation(context, user);
				String content = msg.keywordArguments().get("content").asText();

				final boolean isIncoming = !id.equals(Controller.getInstance().getId());
				Message m = new Message(user, content, user.getUsername(), user, isIncoming, null, isIncoming ? SendingStatus.RECEIVED : SendingStatus.SENT, MessageType.fromString(type));
				
				boolean isGoodChannel = false;
				DatabaseHelper.getInstance().addMessage(m, user.getId(), user.getNamespace());
				if(MinouApplication.getCurrentActivity() instanceof ChatActivity){
					if(user.getNamespace().equals(((ChatActivity) MinouApplication.getCurrentActivity()).getNamespace())){
						isGoodChannel = true;
						DatabaseHelper.getInstance().updateMessageAsRead(m.getId().toString());
					}
				}
				for(EventsListener el : eventsListeners) {
					el.onNewEvent(m);
				}
				
				if((!MinouApplication.isActivityVisible() || !isGoodChannel) && 
						!PreferencesController.isPrivateNotificationsDisabled(context, fullChannelName) 
						&& isIncoming && DatabaseHelper.getInstance().getConversations().contains(user.getId())){
					Log.i(TAG, "Application not visible, should send notification");
					NotificationHelper.notify(context, null, user, content);
				}
			}}, new Action1<Throwable>() {

				@Override
				public void call(Throwable arg0) {
					Log.i(TAG, "Error on private message, error=" + arg0.getMessage());
				}});

		userToCreate.setSubscription(channelSubscription);
		return userToCreate;
	}

	public static void sendMessage(final Message message, final String channelNamespace){
		String fullChannelName = channelNamespace.toLowerCase().replace("-", "_");
		fullChannelName = Normalizer.normalize(fullChannelName, Normalizer.Form.NFD);
		fullChannelName = fullChannelName.replaceAll("\\p{M}", "");
		Log.i(TAG, "sendMessage message=" + message + " fullChannelName=" + fullChannelName);
		client.publish(fullChannelName, new ArrayNode(JsonNodeFactory.instance), getObjectNodeMessage(message.getContent(), message.getMsgType().toString()))
		.forEach(new Action1<Long>(){

			@Override
			public void call(Long arg0) {
				message.setStatus(SendingStatus.SENT);
			}}
		, new Action1<Throwable>(){

			@Override
			public void call(Throwable arg0) {
				message.setStatus(SendingStatus.FAILED);
			}});
	}

	public static void sendFile(final Message message, final String channelNamespace) throws IOException{
		String fullChannelName = channelNamespace.toLowerCase().replace("-", "_");
		fullChannelName = Normalizer.normalize(fullChannelName, Normalizer.Form.NFD);
		fullChannelName = fullChannelName.replaceAll("\\p{M}", "");
		Log.i(TAG, "sendMessage message=picture" + " fullChannelName=" + fullChannelName);
		FileManagerS3.getInstance().uploadFile(message.getContent(), Utils.read(message.getDataPath()), new MinouUploadFileProgressListener(message, channelNamespace));
	}
	
	public static void publishMessage(final Message message, final String channelNamespace){
		String fullChannelName = channelNamespace.toLowerCase().replace("-", "_");
		fullChannelName = Normalizer.normalize(fullChannelName, Normalizer.Form.NFD);
		fullChannelName = fullChannelName.replaceAll("\\p{M}", "");
		client.publish(fullChannelName, new ArrayNode(JsonNodeFactory.instance), getObjectNodeMessage(message.getContent(), message.getMsgType().toString()));
	}

	public static void sendStayAliveMessage(){
		client.publish("heartbeat", new ArrayNode(JsonNodeFactory.instance), getObjectNodeMessage("", MessageType.TEXT.toString()));
	}

	public static void getTopicsCount(){
		ArrayNode an = new ArrayNode(JsonNodeFactory.instance);
		for(String ns : Controller.getInstance().getChannelsContainer().getAllChannelsNamespace()){
			an.add(TextNode.valueOf(ns));
		}
		client.call("plugin.population.topics_count", an, new ObjectNode(JsonNodeFactory.instance))
		.forEach(new Action1<Reply>(){

			@Override
			public void call(Reply reply) {
				Log.i(TAG, "Received topics count");
				JsonNode msg = null;
				Iterator<JsonNode> iterator = reply.arguments().get(0).elements();
				while(iterator.hasNext()){
					msg = iterator.next();
					Log.i(TAG, "Topic=" + msg);
					final String namespace = msg.get("uri").asText();
					final int count = msg.get("count").asInt();
					Controller.getInstance().getChannelsContainer().getChannelByName(namespace).setCount(count);
				}

			}}, new Action1<Throwable>(){

				@Override
				public void call(Throwable throwable) {
					Log.i(TAG, "Get topics count" + throwable.getMessage());
				}});
	}

	public static void getUsers(final ArrayList<String> usersId){
		ArrayNode an = new ArrayNode(JsonNodeFactory.instance);
		for(String userId : usersId){
			an.add(TextNode.valueOf(userId));
		}
		client.call("plugin.profile.get_info", an, new ObjectNode(JsonNodeFactory.instance))
		.forEach(new Action1<Reply>(){

			@Override
			public void call(Reply reply) {
				Log.i(TAG, "Received users");
				JsonNode msg = null;
				Iterator<JsonNode> iterator = reply.arguments().get(0).elements();
				while(iterator.hasNext()){
					msg = iterator.next();
					Log.i(TAG, "User=" + msg);
					final String userId = msg.get("id").asText();
					final String username = msg.get("user_name").asText();
					final String avatarUrl = msg.get("avatar").asText();
					if(DatabaseHelper.getInstance().isAvatarNeededToChange(userId, avatarUrl)) {
						MinouDownloadAvatarProgressListener listener = new MinouDownloadAvatarProgressListener(userId, avatarUrl);
						FileManagerS3.getInstance().downloadFile(avatarUrl, listener);
					}
					DatabaseHelper.getInstance().updateUsername(userId, username);
				}

			}}, new Action1<Throwable>(){

				@Override
				public void call(Throwable throwable) {
					Log.i(TAG, "Get users information" + throwable.getMessage());
				}});
	}

	public static void getTrendingTopics(final Channel channel, final TrendingChannelsListener listener){
		ArrayNode an = new ArrayNode(JsonNodeFactory.instance);
		an.add(TextNode.valueOf(channel.getNamespace() + ".*"));
		an.add(IntNode.valueOf(25));

		client.call("plugin.population.top_topics", an, new ObjectNode(JsonNodeFactory.instance))
		.forEach(new Action1<Reply>(){

			@Override
			public void call(Reply reply) {
				Log.i(TAG, "Received trending topics for=" + channel.getNamespace());
				ArrayList<ChannelTrending> trendings = new ArrayList<ChannelTrending>();
				JsonNode msg = null;
				Iterator<JsonNode> iterator = reply.arguments().get(0).elements();
				while(iterator.hasNext()){
					msg = iterator.next();
					Log.i(TAG, "Topic=" + msg);
					final String namespace = msg.get("uri").asText();
					final int count = msg.get("count").asInt();
					ChannelTrending channel = new ChannelTrending(namespace, count);
					trendings.add(channel);
				}

				if(listener != null){
					listener.onTrendingChannelsFetched(trendings);
				}
			}}, new Action1<Throwable>(){

				@Override
				public void call(Throwable throwable) {
					if(listener != null){
						listener.onTrendingChannelsError(throwable);
					}
				}});
	}

	private static void getLastMessages(final Topic topic){
		ArrayNode an = new ArrayNode(JsonNodeFactory.instance);
		an.add(TextNode.valueOf(topic.getNamespace()));
		client.call("plugin.history.fetch", an, new ObjectNode(JsonNodeFactory.instance))
		.forEach(new Action1<Reply>(){

			@Override
			public void call(Reply reply) {
				Log.i(TAG, "Received missed messages for=" + topic.getNamespace() + " arguments=" + reply.arguments());
				JsonNode msg = null;
				Iterator<JsonNode> iterator = reply.arguments().get(0).elements();
				while(iterator.hasNext()){
					msg = iterator.next();
					Log.i(TAG, "Message=" + msg);
					final String type = msg.get("type").asText();
					final String id = msg.get("user").asText();
					final User user = DatabaseHelper.getInstance().getUser(id);
					final long sentAt = msg.get("sent_at").asLong() / 1000;
					String content = msg.get("content").asText();

					if(!DatabaseHelper.getInstance().isContainMessage(id, content, type, topic.getNamespace())){
						final boolean isIncoming = !id.equals(Controller.getInstance().getId());
						Message m = new Message(user, content, user.getUsername(), topic, isIncoming, null, isIncoming ? SendingStatus.RECEIVED : SendingStatus.SENT, MessageType.fromString(type));
						
						for(EventsListener el : eventsListeners) {
							el.onNewEvent(m);
						}
	
						DatabaseHelper.getInstance().addMessage(m, user.getId(), topic.getNamespace(), sentAt, false);
						if(MinouApplication.getCurrentActivity() instanceof ChatActivity){
							if(user.getNamespace().equals(((ChatActivity) MinouApplication.getCurrentActivity()).getNamespace())){
								DatabaseHelper.getInstance().updateMessageAsRead(m.getId().toString());
							}
						}
					}
				}
			}}, new Action1<Throwable>() {

				@Override
				public void call(Throwable arg0) {
					Log.i(TAG, "Error on last messages, error=" + arg0.getMessage());
				}});
	}

	private static void getLastPrivateMessages(final User userMessages){
		ArrayNode an = new ArrayNode(JsonNodeFactory.instance);
		an.add(userMessages.getNamespace());
		final long lastMessage = DatabaseHelper.getInstance().getLastMessageFetched(userMessages.getNamespace());
		an.add(lastMessage);
		client.call("plugin.history.fetch", an, new ObjectNode(JsonNodeFactory.instance))
		.forEach(new Action1<Reply>(){

			@Override
			public void call(Reply reply) {
				Log.i(TAG, "LastMessage=" + lastMessage + " Received missed messages for=" + userMessages.getNamespace() + " arguments=" + reply.arguments());
				JsonNode msg = null;
				Iterator<JsonNode> iterator = reply.arguments().get(0).elements();
				while(iterator.hasNext()){
					msg = iterator.next();
					Log.i(TAG, "Message=" + msg);
					final String type = msg.get("type").asText();
					final String id = msg.get("user").asText();
					final User user = DatabaseHelper.getInstance().getUser(id);
					final long sentAt = msg.get("sent_at").asLong() / 1000;
					String content = msg.get("content").asText();

					if(!DatabaseHelper.getInstance().isContainMessage(id, content, type, userMessages.getNamespace())){
						final boolean isIncoming = !id.equals(Controller.getInstance().getId());
						Message m = new Message(user, content, user.getUsername(), userMessages, isIncoming, null, isIncoming ? SendingStatus.RECEIVED : SendingStatus.SENT, MessageType.fromString(type));
						
						for(EventsListener el : eventsListeners) {
							el.onNewEvent(m);
						}
						DatabaseHelper.getInstance().addMessage(m, user.getId(), userMessages.getNamespace(), sentAt, false);
						if(MinouApplication.getCurrentActivity() instanceof ChatActivity){
							if(user.getNamespace().equals(((ChatActivity) MinouApplication.getCurrentActivity()).getNamespace())){
								DatabaseHelper.getInstance().updateMessageAsRead(m.getId().toString());
							}
						}
					}
				}
			}}, new Action1<Throwable>() {

				@Override
				public void call(Throwable arg0) {
					Log.i(TAG, "Error on last private messages, error=" + arg0.getMessage());
				}});
	}

	private static ObjectNode getObjectNodeMessage(final String message, final String messageType){
		ObjectNode ob = new ObjectNode(JsonNodeFactory.instance);
		ob.put("from", Controller.getInstance().getId());
		ob.put("user_name", Controller.getInstance().getMyself().getUsername());
		ob.put("content", message);
		ob.put("content_type", messageType);
		return ob;
	}

	public static void addUserAuthenticatedListener(UserAuthenticatedListener listener) {
		userAuthenticatedListeners.add(listener);
	}

	public static void removeUserAuthenticatedListener(UserAuthenticatedListener listener) {
		userAuthenticatedListeners.remove(listener);
	}

	public static void addEventsListener(EventsListener listener) {
		eventsListeners.add(listener);
	}

	public static void removeEventsListener(EventsListener listener) {
		eventsListeners.remove(listener);
	}

	public static void addCrossbarConnectionListener(CrossbarConnectionListener listener) {
		connectionListeners.add(listener);
	}

	public static void removeCrossbarConnectionListener(CrossbarConnectionListener listener) {
		connectionListeners.remove(listener);
	}

	public static boolean isConnected(){
		return isConnected;
	}

	public static void changeUsername(final String newUsername, final UsernameListener listener) {
		AsyncTask<String, Void, String> request = new AsyncTask<String, Void, String>() {

			@Override
			protected String doInBackground(String... arg0) {
				String finalAddress = address + "me";
				List<NameValuePair> data = new ArrayList<NameValuePair>();
				data.add(new BasicNameValuePair("user_name", arg0[0]));
				Log.i(TAG, "New username: " + arg0[0]);
				List<NameValuePair> headers = new ArrayList<NameValuePair>();
				headers.add(new BasicNameValuePair("X-User-Token", arg0[1]));
				HTTPRequest request = new HTTPRequest(finalAddress, RequestType.PUT, data, headers);
				return request.getOutput();
			}

			@Override
			protected void onPostExecute(String result) {
				super.onPostExecute(result);
				Log.i(TAG, "Auth response's json: "+ result);
				
				if(result == null || result.contains("error")) {
					if(listener != null) {
						listener.onUsernameUploadError();
					}
				} else {
					if(listener != null) {
						listener.onUsernameUploaded(newUsername);
					}
				}
			}

		};
		request.execute(newUsername, Controller.getInstance().getToken());
	}
	
	public static void changeAvatar(final String avatarUrl, final byte[] avatar, final AvatarUploadListener listener) {
		AsyncTask<String, Void, String> request = new AsyncTask<String, Void, String>() {

			@Override
			protected String doInBackground(String... arg0) {
				String finalAddress = address + "me";
				List<NameValuePair> data = new ArrayList<NameValuePair>();
				data.add(new BasicNameValuePair("avatar", arg0[0]));
				Log.i(TAG, "New avatar url: " + arg0[0]);
				List<NameValuePair> headers = new ArrayList<NameValuePair>();
				headers.add(new BasicNameValuePair("X-User-Token", arg0[1]));
				HTTPRequest request = new HTTPRequest(finalAddress, RequestType.PUT, data, headers);
				return request.getOutput();
			}

			@Override
			protected void onPostExecute(String result) {
				super.onPostExecute(result);
				Log.i(TAG, "Auth response's json: "+ result);
				
				if(result == null || result.contains("error")){
					if(listener != null){
						listener.onAvatarUploadError();
					}
				} else {
					if(listener != null){
						listener.onAvatarUploaded(avatarUrl, avatar);
					}
				}
			}

		};
		request.execute(avatarUrl, Controller.getInstance().getToken());
	}

	public static void getMyself() {
		AsyncTask<String, Void, String> request = new AsyncTask<String, Void, String>() {

			@Override
			protected String doInBackground(String... arg0) {
				String finalAddress = address + "me";
				List<NameValuePair> headers = new ArrayList<NameValuePair>();
				headers.add(new BasicNameValuePair("X-User-Token", arg0[0]));
				HTTPRequest request = new HTTPRequest(finalAddress, RequestType.GET, null, headers);
				return request.getOutput();
			}

			@Override
			protected void onPostExecute(String result) {
				super.onPostExecute(result);
				Log.i(TAG, "Auth response's json: "+ result);
			}

		};
		request.execute(Controller.getInstance().getToken());
	}  

	private static void readAuth(Reader in) throws IOException {
		JsonReader reader = new JsonReader(in);
		reader.beginObject();
		String userName = "";
		while(reader.hasNext()){
			String name = reader.nextName();
			if (name.equals("token")) {
				Controller.getInstance().setToken(reader.nextString());
			} else if (name.equals("user_name")) {
				userName = reader.nextString();
			} else if (name.equals("secret")) {
				Controller.getInstance().setSecret(reader.nextString());
			} else if (name.equals("id")) {
				Controller.getInstance().setId(reader.nextString());
			} else if (name.equals("role")) {
				Controller.getInstance().setRole(Roles.fromString(reader.nextString()));
			}
		}
		reader.endObject();
		reader.close();
		User user = DatabaseHelper.getInstance().getUser(Controller.getInstance().getId());
		
		if(!user.getUsername().equals(Controller.getInstance().getId())){
			Log.i(TAG, "Setting user from cache");
			Controller.getInstance().setMyOwnUser(user);
		} else{
			user = new User(userName, Channel.BASE_CHANNEL + Controller.getInstance().getId(), AvatarGenerator.generate(Controller.getInstance().getDimensionAvatar(), Controller.getInstance().getDimensionAvatar()), Controller.getInstance().getId(), false);
			Controller.getInstance().setMyOwnUser(user);
			DatabaseHelper.getInstance().addUser(user);
		}
	}
}

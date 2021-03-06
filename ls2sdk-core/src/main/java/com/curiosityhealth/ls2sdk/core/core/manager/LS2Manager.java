package com.curiosityhealth.ls2sdk.core.core.manager;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
//import android.util.Log;

import com.curiosityhealth.ls2sdk.common.LS2ConcreteDatapointConverter;
import com.curiosityhealth.ls2sdk.core.LS2ParticipantAccountGeneratorCredentials;
import com.curiosityhealth.ls2sdk.common.LS2ConcreteDatapoint;
import com.curiosityhealth.ls2sdk.common.LS2Datapoint;
import com.curiosityhealth.ls2sdk.core.core.client.LS2Client;
import com.curiosityhealth.ls2sdk.core.core.client.exception.LS2ClientDataPointConflict;
import com.curiosityhealth.ls2sdk.core.core.client.exception.LS2ClientInvalidDataPoint;
import com.curiosityhealth.ls2sdk.core.core.manager.exception.LS2ManagerDoesNotHaveCredentials;
import com.curiosityhealth.ls2sdk.core.core.manager.exception.LS2ManagerHasCredentials;
import com.curiosityhealth.ls2sdk.core.core.manager.exception.LS2ManagerNotSignedIn;
import com.curiosityhealth.ls2sdk.core.omh.OMHDataPoint;
import com.google.gson.Gson;
import com.squareup.tape2.ObjectQueue;
import com.squareup.tape2.QueueFile;

import org.researchsuite.researchsuiteextensions.encryption.RSClearEncryptor;
import org.researchsuite.researchsuiteextensions.encryption.RSEncryptor;
import org.researchsuite.researchsuiteextensions.common.RSCredentialStore;

import java.io.File;
import java.io.IOException;

/**
 * Created by jameskizer on 3/14/18.
 */

public class LS2Manager {

    //for backwards compatibility
    public static LS2Datapoint convertDatapoint(OMHDataPoint datapoint) {
        //serialize OMHDatapoint
        String jsonString = datapoint.toJson().toString();
        //deserialize to LS2Datapoint
        LS2Datapoint ls2Datapoint = LS2Datapoint.Companion.getGson().fromJson(jsonString, LS2ConcreteDatapoint.class);
        return ls2Datapoint;
    }

    public interface Completion {
        void onCompletion(Exception e);
    }

    public interface Delegate {
        void onInvalidToken(LS2Manager manager);
        void onSignIn(LS2Manager manager);
        void onSignOut(LS2Manager manager);
    }

    public interface Provider {
        @Nullable
        LS2Manager getManager();
    }

    final static String TAG = LS2Manager.class.getSimpleName();

    private static String AUTHENTICATION_TOKEN = "AuthenticationToken";
    private static String USERNAME = "Username";
    private static String PASSWORD = "Password";

    private static LS2Manager manager = null;
    private static Object managerLock = new Object();

    private RSCredentialStore credentialStore;
    private String authToken;
    private Object credentialsLock;
    private boolean credentialStoreUnlocked;

    private Context context;

    private LS2Client client;

    private Object uploadLock;

    private ObjectQueue<LS2Datapoint> datapointQueue;

//    ObjectQueue.Listener<LS2Datapoint> queueListener;
    boolean isUploading = false;

    private Delegate delegate;

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    private static LS2Manager.Provider sProvider;

    @Nullable
    public static Provider getProvider() {
        return sProvider;
    }

    public static void setProvider(Provider sProvider) {
        LS2Manager.sProvider = sProvider;
    }

    @Nullable
    public static LS2Manager getInstance() {
        Provider provider = getProvider();
        if (provider != null) {
            return provider.getManager();
        }
        else {
            return null;
        }
    }

    @Nullable
    private String getAuthToken() {
        //if local authToken is null, try to load
        if (this.authToken == null) {
            byte[] authTokenData = this.credentialStore.get(context, AUTHENTICATION_TOKEN);
            if (authTokenData != null) {
                String authToken = new String(authTokenData);
                if (authToken != null  && !authToken.isEmpty()) {
                    this.authToken = authToken;
                }
            }
        }

        return this.authToken;
    }

    @Nullable
    public String getUsername() {
        //if local authToken is null, try to load
        byte[] usernameData = this.credentialStore.get(context, USERNAME);
        if (usernameData != null) {
            String username = new String(usernameData);
            return username;
        }
        else {
            return null;
        }
    }

    @Nullable
    private String getPassword() {
        //if local authToken is null, try to load
        byte[] passwordData = this.credentialStore.get(context, PASSWORD);
        if (passwordData != null) {
            String password = new String(passwordData);
            return password;
        }
        else {
            return null;
        }
    }

    static ObjectQueue<LS2Datapoint> createDatapointQueue(
            Context context,
            String queueStorageDirectory,
            RSEncryptor encryptor,
            Gson gson
    ) throws IOException {

        File directory = new File(context.getFilesDir(), queueStorageDirectory);
        directory.mkdirs();

//        File queueFile = new

        File qf = new File(directory, "ls2sdk.queue");

        QueueFile queueFile = (new QueueFile.Builder(qf)).build();

        LS2ConcreteDatapointConverter datapointConverter = new LS2ConcreteDatapointConverter(
                encryptor,
                gson
        );

        ObjectQueue<LS2Datapoint> datapointQueue = ObjectQueue.create(queueFile, datapointConverter);
        return datapointQueue;
    }

    public LS2Manager(Context context, String baseURL, RSCredentialStore store, String queueStorageDirectory, RSEncryptor queueEncryptor) {

        this.context = context;
        this.client = new LS2Client(baseURL);

        this.credentialsLock = new Object();

        this.credentialStore = store;

        this.credentialStoreUnlocked = false;

        //load queue from disk
        this.uploadLock = new Object();

//        File queueFile = new File(context.getFilesDir() + queueStorageDirectory);
//        DatapointConverter converter = new DatapointConverter();
        try {

            this.datapointQueue = LS2Manager.createDatapointQueue(
                    context,
                    queueStorageDirectory,
                    queueEncryptor,
                    LS2Datapoint.Companion.getGson()
            );
        } catch(IOException e) {
            e.printStackTrace();
        }

//        this.queueListener = new QueueListener(this);
        //this calls onAdd for each element on queue
//        this.datapointQueue.setListener(this.queueListener);
//        this.datapointQueue.setListener(this);

        //try to upload any existing datapoints
//        this.upload();

    }

    public void setCredentialStoreUnlocked(boolean credentialStoreUnlocked) {
        this.credentialStoreUnlocked = credentialStoreUnlocked;
        if (credentialStoreUnlocked) {
            this.getAuthToken();
            this.upload();
        }
    }

    public boolean isSignedIn() {
        synchronized (this.credentialsLock) {
            return this.getAuthToken() != null && !this.getAuthToken().isEmpty();
        }
    }

    public boolean hasCredentials() {
        synchronized (this.credentialsLock) {
            byte[] usernameData = this.credentialStore.get(context, USERNAME);
            byte[] passwordData = this.credentialStore.get(context, PASSWORD);
            if (usernameData != null && passwordData != null) {
                return true;
            }
            else {
                return false;
            }
        }
    }

    private void setCredentials(String username, String password) {
        synchronized (this.credentialsLock) {
            byte[] usernameData = username.getBytes();
            this.credentialStore.set(context, USERNAME, usernameData);

            byte[] passwordData = password.getBytes();
            this.credentialStore.set(context, PASSWORD, passwordData);
        }
    }

    private void setAuthToken(String authToken) {
        synchronized (this.credentialsLock) {
            this.authToken = authToken;
            byte[] authTokenData = authToken.getBytes();
            this.credentialStore.set(context, AUTHENTICATION_TOKEN, authTokenData);
        }
    }

    private void clearCredentials() {

        //clear queue as well
        this.clearDatapointQueue();

        synchronized (this.credentialsLock) {
            this.authToken = null;
            this.credentialStore.remove(context, AUTHENTICATION_TOKEN);
            this.credentialStore.remove(context, USERNAME);
            this.credentialStore.remove(context, PASSWORD);
        }

    }

    private void clearDatapointQueue() {
        try {
            this.datapointQueue.clear();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void checkTokenIsValid() {

    }

    public void generateParticipantAccount(LS2ParticipantAccountGeneratorCredentials generatorCredentials, final Completion completion) {

        if (this.hasCredentials()) {
            completion.onCompletion(new LS2ManagerHasCredentials());
            return;
        }

        this.client.generateParticipantAccount(generatorCredentials, new LS2Client.GenerationCompletion() {
            @Override
            public void onCompletion(LS2Client.ParticipantAccountGenerationResponse response, Exception e) {

                if (e != null) {
                    completion.onCompletion(e);
                    return;
                }

                if (response != null) {
                    setCredentials(response.getUsername(), response.getPassword());
                }

                if (LS2Manager.this.delegate != null) {
                    LS2Manager.this.delegate.onSignIn(LS2Manager.this);
                }

                completion.onCompletion(null);
                return;

            }
        });

    }

    public void signInWithCredentials(final Completion completion) {

        String username = this.getUsername();
        String password = this.getPassword();

        if (username == null || password == null) {
            completion.onCompletion(new LS2ManagerDoesNotHaveCredentials());
        }

        this.signIn(username, password, completion);
    }

    //Sign In
    public void signIn(String username, String password, final Completion completion) {

//        if (this.isSignedIn()) {
//            completion.onCompletion(new LS2ManagerAlreadySignIn());
//            return;
//        }

        assert(this.credentialStoreUnlocked);

        this.client.signIn(username, password, new LS2Client.AuthCompletion() {
            @Override
            public void onCompletion(LS2Client.SignInResponse response, Exception e) {
                if (e != null) {
                    completion.onCompletion(e);
                    return;
                }

                if (response != null) {
                    setAuthToken(response.getAuthToken());
                }

                if (LS2Manager.this.delegate != null) {
                    LS2Manager.this.delegate.onSignIn(LS2Manager.this);
                }

                completion.onCompletion(null);
                return;
            }
        });

    }

    public void signOut(final Completion completion) {

        if (this.isSignedIn()) {
            this.client.signOut(this.getAuthToken(), new LS2Client.SignOutCompletion() {
                @Override
                public void onCompletion(Boolean success, Exception e) {
                    LS2Manager.this.clearDatapointQueue();
                    LS2Manager.this.clearCredentials();
                    if (LS2Manager.this.delegate != null) {
                        LS2Manager.this.delegate.onSignOut(LS2Manager.this);
                    }
                    completion.onCompletion(null);
                }
            });
        }
        else {
            this.clearDatapointQueue();
            this.clearCredentials();
            if (LS2Manager.this.delegate != null) {
                LS2Manager.this.delegate.onSignOut(LS2Manager.this);
            }
            completion.onCompletion(null);
        }
    }


    public void addDatapoint(final OMHDataPoint datapoint, final Completion completion) {

        if (!this.isSignedIn()) {
            completion.onCompletion(new LS2ManagerNotSignedIn());
            return;
        }

        if (!this.client.validateSample(datapoint)) {
//            Log.w(TAG, "Dropping datapoint, it looks like it's invalid: " + datapoint.toJson().toString());
//            Log.w(TAG, datapoint);
            completion.onCompletion(new LS2ClientInvalidDataPoint());
            return;
        }

        //convert to LS2Datapoint
        LS2Datapoint ls2Datapoint = LS2Manager.convertDatapoint(datapoint);
        this.addDatapoint(ls2Datapoint, completion);
    }

    public void addDatapoint(final LS2Datapoint datapoint, final Completion completion) {
        if (!this.isSignedIn()) {
            completion.onCompletion(new LS2ManagerNotSignedIn());
            return;
        }

        if (!this.client.validateSample(datapoint)) {
//            Log.w(TAG, "Dropping datapoint, it looks like it's invalid: " + datapoint.getHeader().getId());
//            Log.w(TAG, datapoint);
            completion.onCompletion(new LS2ClientInvalidDataPoint());
            return;
        }

        try {
            this.datapointQueue.add(datapoint);
            this.upload();
            completion.onCompletion(null);
        } catch (IOException e) {
            e.printStackTrace();
            completion.onCompletion(e);
        }

    }


    private void tryToUpload() {

        assert(this.isSignedIn());
        assert(this.credentialStoreUnlocked);

        synchronized (this.uploadLock) {

            if (this.isUploading) {
                return;
            }

            if (this.datapointQueue.size() < 1) {
                return;
            }

            this.isUploading = true;

//            String datapointString = this.datapointQueue.peek();

            LS2Datapoint datapoint = null;

            try {
                datapoint = this.datapointQueue.peek();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

//            assert(datapointString != null && !datapointString.isEmpty());

            String localAuthToken;
            synchronized (this.credentialsLock) {
                localAuthToken = this.getAuthToken();
            }

            assert(localAuthToken != null && !localAuthToken.isEmpty());

            this.client.postSample(datapoint, localAuthToken, new LS2Client.PostSampleCompletion() {
                @Override
                public void onCompletion(boolean success, Exception e) {

//                    OhmageOMHManager.this.isUploading = false;

                    if (success) {
//                        Log.w(TAG, "Datapoint successfully uploaded");
                        try {
                            LS2Manager.this.datapointQueue.remove();
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }

                        LS2Manager.this.isUploading = false;
                        LS2Manager.this.upload();
                        return;
                    }

//                    Log.e(TAG, "Got an exception trying to upload datapoint", e);

                    if (e instanceof LS2ClientDataPointConflict ||
                            e instanceof LS2ClientInvalidDataPoint){

                        try {
                            LS2Manager.this.datapointQueue.remove();
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }
                        LS2Manager.this.isUploading = false;
                        LS2Manager.this.upload();
                        return;
                    }

                    else {
                        LS2Manager.this.isUploading = false;
                        return;
                    }

                }
            });

        }
    }

    private void upload() {


        if (!this.credentialStoreUnlocked || !this.isSignedIn()) { return; }

        //start async task here

        class UploadTask extends AsyncTask<Void, Void, Void> {

            @Override
            protected Void doInBackground(Void... params) {

                LS2Manager.this.tryToUpload();

                return null;

            }
        }

        new UploadTask().execute();

    }

//    @Override
//    public void onAdd(ObjectQueue<String> queue, String entry) {
//        manager.upload();
//    }
//
//    @Override
//    public void onRemove(ObjectQueue<String> queue) {
//
//    }


}

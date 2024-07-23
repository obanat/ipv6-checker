package com.obana.ipv6;

import android.app.Activity;

import android.content.Context;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.net.ConnectivityManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.obana.ipv6.utils.AppLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Enumeration;

import javax.net.SocketFactory;

public class MainActivity extends Activity implements View.OnClickListener {
    public static final String TAG = "MainActivity";
    public static final int LAYOUT_CLIENT = 100;
    public static final int LAYOUT_SERVER = 101;
    public static final int LAYOUT_DEFAULT = LAYOUT_CLIENT;
    private static final String UPLOAD_IP_URL = "http://obana.f3322.org:38086/wificar/regClient?";

    public static final int MESSAGE_UPLOAD_IP_STATUS = 1001;
    public static final int MESSAGE_RECONNECT_TO_CAMERA = 1003;
    public static final int MESSAGE_UPDATE_PUBLIC_IP = 1002;
    public static final int MESSAGE_GET_SERVER_IPV6 = 1004;
    public static final int MESSAGE_UPDATE_LOCAL_IP = 1005;

    public static final int MESSAGE_UPDATE_CLIENT_IP = 1006;
    public static final int MESSAGE_CONNECT_TO_SERVER = 1007;
    public static final int MESSAGE_PUNCHING_STATUS = 1008;
    public static final int MESSAGE_MAKE_TOAST = 6001;
    public static final boolean SHOW_DEBUG_MESSAGE = true;
    public static final String BUNDLE_KEY_TOAST_MSG = "Tmessage";

    public static final int RECONNECT_DELAY_MS = 5000;
    public static final int HTTP_SERVER_PORT = 38000;

    private static final int CLIENT_LOCAL_SOCKET_PORT = 35555;
    private static final int SERVER_LISTEN_SOCKET_PORT = 35555;

    private static final int PUNCHING_STATUS_ERROR_IP = 1001;
    private static final int PUNCHING_STATUS_ERROR_SOCKET = 1002;

    private static final int PUNCHING_STATUS_ERROR_OK = 1000;
    private Handler handler = null;
    private int mWorkMode = LAYOUT_DEFAULT;

    private RadioButton mRadioClient,mRadioServer;
    private TextView mTextViewIpv6,mTextViewIpv6Local;
    private TextView mTextViewNetworkName,mTextViewNetworkType;
    private TextView mTextViewClientIp,mTextViewPunchingStatus;

    private TextView mTextViewConnect2ServerStatus;
    private Network mWifiNetwork;
    private Network mCellNetwork;

    //client
    private WebView mWeb;
    private Button mLoadWebBtn;
    private TextView mTextViewServerIpv6;

    //server
    private TextView mUploadStatus, mHttpStatus;
    private HttpServer mHttpServer;

    private String mServerIpv6Str;
    private ImageButton mSetttingsBtn;


    private PowerManager.WakeLock mWakeLock;
    TelephonyManager mTm;

    protected void onCreate(Bundle paramBundle) {
        super.onCreate(paramBundle);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.client);

        mRadioClient = findViewById(R.id.radio_client);
        mRadioServer = findViewById(R.id.radio_server);

        mTextViewIpv6 = findViewById(R.id.ipv6_public);
        mTextViewIpv6Local = findViewById(R.id.ipv6_local);

        mTextViewNetworkName = findViewById(R.id.op_name);
        mTextViewNetworkType = findViewById(R.id.network_type);

        mUploadStatus = findViewById(R.id.upload_status);

        mSetttingsBtn = findViewById(R.id.setting_button);


        this.handler = new Handler() {
            public void handleMessage(Message param1Message) {
                if (!handleMessageinUI(param1Message)) {
                    super.handleMessage(param1Message);
                }
            }
        };

        PowerManager powerManager = (PowerManager)getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "NEW:WakeLock");
        }
        mTm = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);


        mSetttingsBtn.setOnClickListener(this);
        initClientLayout();//client is default view
        mRadioClient.setOnClickListener(this);
        mRadioServer.setOnClickListener(this);

        String carrierName = mTm.getNetworkOperatorName();
        mTextViewNetworkName.setText(carrierName);
        refreshNetworkType();
        requestNetwork();

        getPublicIpv6(null);
    }

    private void switchLayout(int workMode) {
        if (workMode ==  LAYOUT_CLIENT ) {
            setContentView(R.layout.client);
            initClientLayout();
        } else {
            setContentView(R.layout.server);
            initServerLayout();


            //first punching hole
            if (!clientIp.isEmpty()) sentPunchingSignal(mCellNetwork, clientIp, CLIENT_LOCAL_SOCKET_PORT);

            //then create server socket
            prepareServer();
        }
        String carrierName = mTm.getNetworkOperatorName();
        mTextViewNetworkName.setText(carrierName);
        //refreshNetworkType();
        //requestNetwork();

        //getPublicIpv6(null);

        Message msg1 = new Message();
        msg1.what = MESSAGE_UPDATE_LOCAL_IP;
        handler.sendMessage(msg1);

        Message msg2 = new Message();
        msg2.what = MESSAGE_UPDATE_CLIENT_IP;
        handler.sendMessage(msg2);

        Message msg3 = new Message();
        msg3.what = MESSAGE_UPDATE_PUBLIC_IP;
        handler.sendMessage(msg3);

        refreshNetworkType();
    }

    private void initClientLayout() {
        //mRadioClient.setChecked(true);
        //mRadioServer.setChecked(false);

        mRadioClient = findViewById(R.id.radio_client);
        mRadioServer = findViewById(R.id.radio_server);

        mTextViewIpv6 = findViewById(R.id.ipv6_public);
        mTextViewIpv6Local = findViewById(R.id.ipv6_local);

        mTextViewNetworkName = findViewById(R.id.op_name);
        mTextViewNetworkType = findViewById(R.id.network_type);

        mWeb = findViewById(R.id.web_view);
        mLoadWebBtn = findViewById(R.id.load_web);
        mTextViewServerIpv6 = findViewById(R.id.server_ip);

        mTextViewConnect2ServerStatus = findViewById(R.id.connect_status);
        mLoadWebBtn.setOnClickListener(this);
    }

    private void prepareClient() {
        //getServerIpv6();

    }
    private ServerSocket serverSocket = null;
    private void prepareServer() {
        if (mHttpServer == null) {
            mHttpServer = new HttpServer(HTTP_SERVER_PORT, this);
        }
        try{
            if (!mHttpServer.wasStarted()) {
                mHttpServer.start();
            }

            serverSocket = serverSocket != null ? serverSocket : new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(SERVER_LISTEN_SOCKET_PORT));

            mHttpStatus.setText("http server success,listening " + SERVER_LISTEN_SOCKET_PORT + " ....");
        }catch(Exception e){
            AppLog.e(TAG,"start http server error:"+e.getMessage());
            mHttpStatus.setText("http server failed");
        }
    }


    private void initServerLayout() {
        //mRadioClient.setChecked(false);
        //mRadioServer.setChecked(true);

        mRadioClient = findViewById(R.id.radio_client);
        mRadioServer = findViewById(R.id.radio_server);

        mTextViewIpv6 = findViewById(R.id.ipv6_public);
        mTextViewIpv6Local = findViewById(R.id.ipv6_local);

        mTextViewNetworkName = findViewById(R.id.op_name);
        mTextViewNetworkType = findViewById(R.id.network_type);

        mUploadStatus = findViewById(R.id.upload_status);
        mHttpStatus = findViewById(R.id.http_status);

        mTextViewClientIp = findViewById(R.id.ipv6_client);
        mTextViewPunchingStatus = findViewById(R.id.punching_status);

    }

    private Socket clientSocket = null;
    private int clientSocketLocalPort = -1;
    public void requestNetwork() {
        mWifiNetwork = null;
        mCellNetwork = null;

        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        NetworkRequest build = builder.build();
        AppLog.i(TAG, "---> start request cell network");
        connectivityManager.requestNetwork(build, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                AppLog.i(TAG, "---> network request: cell OK");
                mCellNetwork = network;
                refreshNetworkType();
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                getLocalIpv6(network,cm);
                getPublicIpv6(network);

                getServerIpv6(network);
                getClientIpv6(network);
                if (mWorkMode == LAYOUT_CLIENT && clientSocket == null) {
                    try {
                        clientSocket = SocketFactory.getDefault().createSocket();
                        network.bindSocket(clientSocket);
                        clientSocket.setReuseAddress(true);
                        clientSocket.bind(new InetSocketAddress(CLIENT_LOCAL_SOCKET_PORT));
                        clientSocketLocalPort = clientSocket.getLocalPort();

                        mTextViewConnect2ServerStatus.setText(""+clientSocketLocalPort);
                    } catch (IOException e) {
                        AppLog.e(TAG, "---> network request: e:" + e.getMessage());
                    }

                }

            }
        });

        builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        build = builder.build();
        AppLog.i(TAG, "---> start request wifi network");
        connectivityManager.requestNetwork(build, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                AppLog.i(TAG, "---> network request: wifi OK");
                mWifiNetwork = network;
                refreshNetworkType();
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                getLocalIpv6(network,cm);
                getPublicIpv6(network);
                if (mWorkMode == LAYOUT_CLIENT) {
                    getServerIpv6(network);
                } else {
                    //uploadServerIp(network);
                }
            }
        });
    }

    private void refreshNetworkType() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mWifiNetwork != null && mCellNetwork != null) {
                    mTextViewNetworkType.setText("wifi+cell");
                } else if (mWifiNetwork != null && mCellNetwork == null) {
                    mTextViewNetworkType.setText("wifi");
                } else if (mWifiNetwork == null && mCellNetwork != null) {
                    mTextViewNetworkType.setText("cell");
                } else {
                    mTextViewNetworkType.setText("null");
                }
            }
        });
    }

    @Override
    public void onClick(View view) {

        if (view == mSetttingsBtn) {
            //startActivity(new Intent(this, Settings.class));
        } else if (view == mRadioClient) {
            mWorkMode = mRadioClient.isChecked() ? LAYOUT_CLIENT : LAYOUT_SERVER;
            switchLayout(mWorkMode);
            if (mWorkMode == LAYOUT_SERVER) {
                mRadioClient.setChecked(false);
                mRadioServer.setChecked(true);
            } else {
                mRadioClient.setChecked(true);
                mRadioServer.setChecked(false);
            }
        } else if (view == mRadioServer) {
            mWorkMode = mRadioServer.isChecked() ? LAYOUT_SERVER : LAYOUT_CLIENT;
            switchLayout(mWorkMode);
            if (mWorkMode == LAYOUT_SERVER) {
                mRadioClient.setChecked(false);
                mRadioServer.setChecked(true);
            } else {
                mRadioClient.setChecked(true);
                mRadioServer.setChecked(false);
            }
        } else if (view == mLoadWebBtn) {
            mWeb.loadUrl("http://obana.f3322.org:38086");
            if (!serverIp.isEmpty() && mCellNetwork != null) connect2Server(mCellNetwork, serverIp, SERVER_LISTEN_SOCKET_PORT);
        }
    }
    public boolean onKeyDown(int paramInt, KeyEvent paramKeyEvent) {
        Log.i(TAG, "onKeyDown key=" + paramInt + " event=" + paramKeyEvent);
        Toast.makeText(this, "k:" + paramInt + " k:" + paramKeyEvent.getKeyCode(), Toast.LENGTH_SHORT).show();
        if (paramInt == 4) {
            finish();
        }
        return super.onKeyDown(paramInt, paramKeyEvent);
    }

    public boolean onKeyUp(int paramInt, KeyEvent paramKeyEvent) {
        return super.onKeyUp(paramInt, paramKeyEvent);
    }

    private void requestSpecifyNetwork() {
        ConnectivityManager conMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (conMgr == null) return;

        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

        NetworkRequest build = builder.build();
        AppLog.i(TAG, "---> start request network ");

        conMgr.requestNetwork(build, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                AppLog.i(TAG, "---> request network OK! start connectRunnable...");
                (new Thread(connectRunnable)).start();

            }
        });
    }

    Runnable connectRunnable = new Runnable() {
        public void run() {

        }
    };

    protected void onResume() {
        super.onResume();

        AppLog.i(TAG, "on Resume");

        if (mWakeLock != null) {
            mWakeLock.acquire();
        }


    }

    protected void onPause() {
        super.onPause();

        if (mWakeLock != null) {
            mWakeLock.release();
        }
        AppLog.i(TAG, "on onPause");
    }


    protected void onStop() {
        super.onStop();
    }

    protected void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "on destory");
    }


    public boolean handleMessageinUI(Message msg) {
        boolean handled = false;
        String tmpString = "";
        switch (msg.what) {
            case MESSAGE_UPDATE_PUBLIC_IP:
                String ip = (String)msg.obj;//TODO: for feature use
                mTextViewIpv6.setText(publicIpV6Address);
                handled = true;
                break;
            case MESSAGE_UPLOAD_IP_STATUS:
                String status = (String)msg.obj;
                if (mUploadStatus != null) mUploadStatus.setText(status);
                handled = true;
                break;
            case MESSAGE_GET_SERVER_IPV6:
                String serverIp = (String)msg.obj;
                mTextViewServerIpv6.setText(serverIp);
                handled = true;
                break;
            case MESSAGE_UPDATE_LOCAL_IP:
                mTextViewIpv6Local.setText("[" + msg.obj + "]\r\n" + localIpV6Address);
                handled = true;
                break;
            case MESSAGE_UPDATE_CLIENT_IP:
                if (mTextViewClientIp != null) mTextViewClientIp.setText(clientIp);
                handled = true;
                break;
            case MESSAGE_PUNCHING_STATUS:
                tmpString = msg.obj != null?(String)msg.obj : "OK...";
                if (mTextViewPunchingStatus != null) mTextViewPunchingStatus.setText(tmpString);
                handled = true;
                break;
            case MESSAGE_CONNECT_TO_SERVER:
                tmpString = msg.obj != null?(String)msg.obj : "OK...";
                if (mTextViewConnect2ServerStatus != null) mTextViewConnect2ServerStatus.setText(tmpString);
                handled = true;
                break;

            default:
                handled = false;
                break;
        }
        return handled;
    }

    private void sendToastMessage(String str) {
        Bundle bundle = new Bundle();
        bundle.putString(BUNDLE_KEY_TOAST_MSG, str);

        Message msg = handler.obtainMessage(MESSAGE_MAKE_TOAST);
        msg.setData(bundle);
        handler.sendMessage(msg);
    }

    private String localIpV6Address = "";
    private void getLocalIpv6(Network network, ConnectivityManager cm) {

        try {
            LinkProperties prop = cm.getLinkProperties(network);
            if (prop == null) {
                return;
            }

            String interfaceName = prop.getInterfaceName();
            NetworkInterface networkInterface = null;

            try {
                networkInterface = NetworkInterface.getByName(interfaceName);
            } catch (Exception e) {

            }
            if (networkInterface == null) {
                return;
            }
            Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses();
            while (enumIpAddr.hasMoreElements()) {
                InetAddress inetAddress = enumIpAddr.nextElement();
                if (inetAddress instanceof Inet6Address && !inetAddress.isLoopbackAddress()
                        && !inetAddress.isLinkLocalAddress()) {

                    localIpV6Address = inetAddress.getHostAddress();
                    localIpV6Address = localIpV6Address.replace("%5", "");
                    AppLog.i(TAG, "get Local IPv6, IP Address:" + localIpV6Address);
                    break;
                }
            }
            Message msg = new Message();
            msg.what = MESSAGE_UPDATE_LOCAL_IP;
            msg.obj = interfaceName;
            handler.sendMessage(msg);
            //mTextViewIpv6Local.setText("[" + interfaceName + "]\r\n" + localIpV6Address);
        } catch (Exception e) {
            AppLog.e(TAG, "get Local IPv6, Exception:" + e.getMessage());
        }
    }

    private boolean networkRunning = false;
    private String publicIpV6Address = "";
    private void getPublicIpv6(final Network network) {
        Runnable getPublicIpv6Runnable = new Runnable() {
            @Override
            public void run() {
                networkRunning = true;
                boolean metError = false;
                byte[] buf = new byte[64];
                URL url = null;
                try {

                    url = new URL("http://6.ipw.cn") ;
                    HttpURLConnection conn = network != null ? (HttpURLConnection) network.openConnection(url) : (HttpURLConnection)url.openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setRequestMethod("GET");
                    if (conn.getResponseCode() != 200) {
                        AppLog.e(TAG, "getPublicIpv6, 6.ipw.cn error! ResponseCode:" + conn.getResponseCode());
                    }
                    InputStream inStream = conn.getInputStream();

                    inStream.read(buf);
                } catch (IOException e) {
                    AppLog.e(TAG, "getPublicIpv6, 6.ipw.cn error! e:" + e.getMessage());
                    metError = true;
                }

                AppLog.i(TAG, "getPublicIpv6 from 6.ipw.cn :" + new String(buf));
                Message msg = new Message();
                msg.what = MESSAGE_UPDATE_PUBLIC_IP;

                if( buf.length > 12 && !metError){
                    msg.obj = new String(buf);
                    publicIpV6Address = new String(buf);
                } else {
                    publicIpV6Address = "error";
                }

                //if (mWorkMode == LAYOUT_SERVER) {
                    uploadIp2Redis(network);
                //}
                handler.sendMessage(msg);
                networkRunning = false;
            }

        };

        //if (mTextViewIpv6.getText().length() == 0) {
            new Thread(getPublicIpv6Runnable).start();
        //}
    }

    private static final String P2P_HOST_URL = "http://obana.f3322.org:38086/wificar/getClientIp?mac=server";
    private String serverIp = "";
    private void getServerIpv6(final Network network) {
        Runnable serverIpv6Runnable = new Runnable() {
            @Override
            public void run() {
                serverIp = "";//clear buffer

                StringBuffer sb = new StringBuffer();
                String hostUrl = P2P_HOST_URL;
                AppLog.i(TAG, "getServerIpv6:  " + hostUrl);

                if (network == null ) return;
                try {
                    URL updateURL = new URL(hostUrl);
                    URLConnection conn = network.openConnection(updateURL);
                    BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF8"));
                    while (true) {
                        String s = rd.readLine();
                        if (s == null) {
                            break;
                        }
                        sb.append(s);
                    }
                } catch (Exception e){

                }

                AppLog.i(TAG, "getServerIpv6, response: " + sb + " len:" + sb.length());
                Message msg = new Message();
                msg.what = MESSAGE_GET_SERVER_IPV6;
                if(sb.length() > 10) {
                    serverIp = sb.toString();
                    AppLog.i(TAG, "getServerIpv6 ip:" + sb);
                    msg.obj = sb.toString();
                }
                handler.sendMessage(msg);
            }
        };
        new Thread(serverIpv6Runnable).start();
    }


    private static final String GET_CLIENT_IP_URL = "http://obana.f3322.org:38086/wificar/getClientIp?mac=client";
    private String clientIp = "";
    private void getClientIpv6(final Network network) {
        Runnable clientIpv6Runnable = new Runnable() {
            @Override
            public void run() {
                clientIp = "";//clear buffer

                StringBuffer sb = new StringBuffer();
                String hostUrl = GET_CLIENT_IP_URL;
                AppLog.i(TAG, "getClientIpv6:  " + hostUrl);

                if (network == null ) return;
                try {
                    URL updateURL = new URL(hostUrl);
                    URLConnection conn = network.openConnection(updateURL);
                    BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF8"));
                    while (true) {
                        String s = rd.readLine();
                        if (s == null) {
                            break;
                        }
                        sb.append(s);
                    }
                } catch (Exception e){

                }

                AppLog.i(TAG, "getClientIpv6, response: " + sb + " len:" + sb.length());
                Message msg = new Message();
                msg.what = MESSAGE_UPDATE_CLIENT_IP;

                AppLog.i(TAG, "getClientIpv6 ip:" + sb);
                msg.obj = sb.toString();
                clientIp = sb.toString();

                handler.sendMessage(msg);
            }
        };
        new Thread(clientIpv6Runnable).start();
    }

    private void sentPunchingSignal(final Network network, final String ip, final int port) {
        //step 1:
        Runnable punchingRunnable = new Runnable() {
            @Override
            public void run() {

                InetAddress addr = null;

                Message msg = new Message();
                msg.what = MESSAGE_PUNCHING_STATUS;

                try {
                    addr = InetAddress.getByName(ip);
                } catch (IOException e) {
                    AppLog.e(TAG, "punching client get ip error!");

                    msg.obj = e.getMessage();
                }
                AppLog.i(TAG, "punching client >>>> ip:port [ IP:" + ip + " port:" + port);

                InetSocketAddress inetSocketAddress = new InetSocketAddress(addr, port);

                AppLog.i(TAG, "punching client >>>> inetSocketAddress:" + inetSocketAddress );
                Socket socket = null;
                int localPort = 0;

                try {
                    socket = SocketFactory.getDefault().createSocket();
                    socket.bind(new InetSocketAddress(CLIENT_LOCAL_SOCKET_PORT));
                    network.bindSocket(socket);
                    socket.setReuseAddress(true);
                    socket.connect(inetSocketAddress, 3000);

                } catch (IOException e) {
                    AppLog.e(TAG, "punching client socket connect failed!!!" + e.getMessage());
                    msg.obj = e.getMessage();
                }

                try {
                    if(socket!=null)socket.close();
                } catch (IOException e) {
                    AppLog.e(TAG, "punching client socket close failed!!!" + e.getMessage());
                }
                socket = null;

                msg.arg1 = PUNCHING_STATUS_ERROR_OK;
                handler.sendMessage(msg);
            }
        };
        new Thread(punchingRunnable).start();
    }

    private void connect2Server(final Network network, final String ip, final int port) {
        Runnable socketRunnable = new Runnable() {
            @Override
            public void run() {
                Message msg = new Message();
                msg.what = MESSAGE_CONNECT_TO_SERVER;

                InetAddress addr = null;
                try {
                    addr = InetAddress.getByName(ip);
                } catch (IOException e) {
                    AppLog.e(TAG, "connect2Server, error!" + e.getMessage());
                    msg.obj = e.getMessage();
                    handler.sendMessage(msg);
                    return;
                }

                AppLog.i(TAG, "connect2Server >>>>> [ IP:" +  ip + " port:" + port);

                InetSocketAddress inetSocketAddress = new InetSocketAddress(addr, port);
                Socket socket;
                int localPort = 0;

                try {
                    if (clientSocket!=null)clientSocket.connect(inetSocketAddress, 3000);
                } catch (IOException e) {
                    AppLog.e(TAG, "connect2Server, error!" + e.getMessage());
                    msg.obj = e.getMessage();
                    handler.sendMessage(msg);
                    return;
                }

                AppLog.i(TAG, "connect2Server, successfully! ");
                handler.sendMessage(msg);
            }
        };
        new Thread(socketRunnable).start();
    }

    public void uploadIp2Redis(final Network network) {

        Runnable uploadRunnable = new Runnable() {
            @Override
            public void run() {
                String strMsg = "null";
                AppLog.i(TAG, "upload ip to redis server...");
                String serverPerfix = "mac=server&time=";
                String clientPerfix = "mac=client&time=";
                try {
                    String data;

                    if (isValidIpV6(publicIpV6Address)) {
                        data = (mWorkMode == LAYOUT_SERVER?serverPerfix:clientPerfix) + System.currentTimeMillis()
                                + "&ipaddr=" + publicIpV6Address;
                    } else {
                        if (isValidIpV6(localIpV6Address)) {
                            data = (mWorkMode == LAYOUT_SERVER?serverPerfix:clientPerfix)  + System.currentTimeMillis()
                                    + "&ipaddr=" + localIpV6Address;
                        } else {
                            strMsg = "upload ip failed!";
                            Message msg = new Message();
                            msg.what = MESSAGE_UPLOAD_IP_STATUS;
                            msg.obj = strMsg;
                            handler.sendMessage(msg);
                            return;
                        }
                    }

                    String urlValue = UPLOAD_IP_URL + data;

                    //AppLog.i(TAG, "server ip: " + data);

                    URL url =new URL(urlValue);
                    HttpURLConnection connection = network != null ? (HttpURLConnection)network.openConnection(url)
                            : (HttpURLConnection)url.openConnection();
                    connection.setDoInput(true);
                    connection.setDoOutput(true);
                    connection.setRequestMethod("POST");

                    connection.setUseCaches(false);
                    connection.connect();

                    connection.getOutputStream().write(data.getBytes());
                    connection.getOutputStream().flush();
                    connection.getOutputStream().close();

                    if (connection.getResponseCode() == 200) {
                        strMsg = "upload ip success";

                        InputStream input = connection.getInputStream();
                        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(input));
                        String result = bufferedReader.readLine();
                        input.close();
                        AppLog.i(TAG, "upload local IpV6 to redis cloud, success!");
                    } else {
                        strMsg = "upload ip failed";
                        //mUploadStatus.setText("upload ip failed");
                        AppLog.e(TAG, "upload local IpV6 to redis cloud, failed resp:" + connection.getResponseCode());
                    }

                } catch (IOException iOException) {
                    AppLog.e(TAG, "uploadIp, failed! e:" + iOException.getMessage());
                    strMsg = "upload ip failed2";
                    //mUploadStatus.setText("upload ip failed2");
                }

                Message msg = new Message();
                msg.what = MESSAGE_UPLOAD_IP_STATUS;
                msg.obj = strMsg;
                handler.sendMessage(msg);
            }
        };
        new Thread(uploadRunnable).start();
    }

    private boolean isValidIpV6(String ip) {
        return !TextUtils.isEmpty(ip) && ip.length() > 10;
    }
}

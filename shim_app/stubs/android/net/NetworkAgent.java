package android.net;

import android.content.Context;
import android.os.Looper;

public abstract class NetworkAgent {
    public NetworkAgent(Context context, Looper looper, String logTag,
            NetworkCapabilities capabilities, LinkProperties properties,
            NetworkScore score, NetworkAgentConfig config, NetworkProvider provider) {}

    public Network register() { return null; }
    public void markConnected() {}
    public void unregister() {}
    public abstract void onNetworkUnwanted();
}

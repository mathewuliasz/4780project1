
import java.io.Serializable;

public class NameServerInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    // Predecessor Info
    public int id;
    public String ipAddress;
    public int port;

    public NameServerInfo() {
    }

    // Each NameServer will store
    public NameServerInfo(int id, String ipAddress, int port) {
        this.id = id;
        this.ipAddress = ipAddress;
        this.port = port;
    }

}

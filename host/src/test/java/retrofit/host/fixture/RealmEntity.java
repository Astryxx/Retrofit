package retrofit.host.fixture;
public final class RealmEntity implements RealmPort {
    private static int updates;
    public int advance() { return ++updates; }
}

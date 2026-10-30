package retrofit.host;
import retrofit.host.fixture.RealmPort;
import java.io.IOException;
public final class RuntimeIsolationCheck {
    private static final String TYPE="retrofit.host.fixture.RealmEntity";
    private static final class Realm extends ClassLoader {
        Realm(){super(RuntimeIsolationCheck.class.getClassLoader());}
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized(getClassLoadingLock(name)) {
                if(!name.equals(TYPE))return super.loadClass(name,resolve);
                Class<?> type=findLoadedClass(name);
                if(type==null)try(var in=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                    if(in==null)throw new ClassNotFoundException(name);
                    byte[] bytes=in.readAllBytes();type=defineClass(name,bytes,0,bytes.length);
                }catch(IOException e){throw new ClassNotFoundException(name,e);}
                if(resolve)resolveClass(type);return type;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Class<?> first=new Realm().loadClass(TYPE), second=new Realm().loadClass(TYPE);
        Object a=first.getConstructor().newInstance(), b=second.getConstructor().newInstance();
        if(first==second || !first.getName().equals(second.getName()))throw new AssertionError("realm class identity");
        boolean refused=false;try{first.cast(b);}catch(ClassCastException expected){refused=true;}
        if(!refused)throw new AssertionError("cross-realm cast accepted");
        if(((RealmPort)a).advance()!=1 || ((RealmPort)a).advance()!=2 || ((RealmPort)b).advance()!=1)throw new AssertionError("static simulation state leaked");
        System.out.println("Realm boundary: same class names have different types, static state stays isolated, parent-owned neutral interface crosses both loaders");
    }
}

package org.jkiss.dbeaver.model.ai.utils;
import java.net.*; import org.jkiss.dbeaver.DBException;
public class AIHttpUtils {
    public static URI resolve(String base, String... paths) throws DBException {
        try {
            String normalizedBase = (paths.length > 0 && !base.endsWith("/")) ? base + "/" : base;
            URI uri = new URI(normalizedBase);
            for (String path : paths) { uri = uri.resolve(path); }
            return uri;
        } catch (URISyntaxException e) { throw new DBException("Incorrect URI", e); }
    }
}

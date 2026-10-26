package io.github.darkstarworks.pluginpulse.velocity;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** A {@link java.util.logging.Logger} that writes to the plugin's SLF4J logger. */
public final class JulBridge {

    private JulBridge() {
    }

    public static Logger wrap(org.slf4j.Logger target) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(target.isDebugEnabled() ? Level.FINE : Level.INFO);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                String msg = record.getMessage();
                Throwable t = record.getThrown();
                int level = record.getLevel().intValue();
                if (level >= Level.SEVERE.intValue()) {
                    target.error(msg, t);
                } else if (level >= Level.WARNING.intValue()) {
                    target.warn(msg, t);
                } else if (level >= Level.INFO.intValue()) {
                    target.info(msg, t);
                } else {
                    target.debug(msg, t);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}

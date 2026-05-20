package com.magizhchi.cloud.tenant;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** Wraps a single JDBC Connection as a non-closing DataSource. */
class SingleConnectionDataSource implements DataSource {
    private final Connection conn;
    SingleConnectionDataSource(Connection conn) { this.conn = conn; }

    @Override public Connection getConnection() { return conn; }
    @Override public Connection getConnection(String u, String p) { return conn; }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getLogger("tenant"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return iface.cast(this); }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}

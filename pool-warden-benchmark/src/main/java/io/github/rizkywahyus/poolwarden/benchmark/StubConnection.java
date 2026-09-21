package io.github.rizkywahyus.poolwarden.benchmark;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A connection that does nothing at all, so a benchmark can measure the warden instead of a
 * driver. Only the methods the benchmark touches are implemented; everything else fails loudly
 * rather than quietly returning something meaningless.
 *
 * <p>Generated shape, hand-kept: {@link Connection} has a lot of methods and none of them
 * matter here.
 */
final class StubConnection implements Connection {

    private boolean closed;
    private boolean autoCommit = true;

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    @Override
    public java.sql.Statement createStatement() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.lang.String nativeSQL(java.lang.String arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commit() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rollback() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setReadOnly(boolean arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setCatalog(java.lang.String arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.lang.String getCatalog() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTransactionIsolation(int arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getTransactionIsolation() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Statement createStatement(int arg0, int arg1) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1, int arg2) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String arg0, int arg1, int arg2) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTypeMap(java.util.Map<java.lang.String, java.lang.Class<?>> arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setHoldability(int arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Savepoint setSavepoint(java.lang.String arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rollback(java.sql.Savepoint arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Statement createStatement(int arg0, int arg1, int arg2) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1, int arg2, int arg3) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String arg0, int arg1, int arg2, int arg3) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int[] arg1) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, java.lang.String[] arg1) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Clob createClob() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Blob createBlob() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.NClob createNClob() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isValid(int arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setClientInfo(java.lang.String arg0, java.lang.String arg1) throws java.sql.SQLClientInfoException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setClientInfo(java.util.Properties arg0) throws java.sql.SQLClientInfoException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.lang.String getClientInfo(java.lang.String arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Properties getClientInfo() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Array createArrayOf(java.lang.String arg0, java.lang.Object[] arg1) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.sql.Struct createStruct(java.lang.String arg0, java.lang.Object[] arg1) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSchema(java.lang.String arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.lang.String getSchema() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void abort(java.util.concurrent.Executor arg0) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor arg0, int arg1) throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getNetworkTimeout() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }
}

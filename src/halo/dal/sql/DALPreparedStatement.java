package halo.dal.sql;

import halo.dal.DALCurrentStatus;
import halo.dal.DALCustomInfo;
import halo.dal.DALFactory;
import halo.dal.DALRunTimeException;
import halo.dal.MultDataSourceOnOperateException;
import halo.dal.ResultErrException;
import halo.dal.analysis.SQLInfo;
import halo.dal.partition.PartitionParser;
import halo.dal.partition.PartitionParserNotFoundException;
import halo.dal.partition.PartitionTableInfo;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;

/**
 * 代理PreparedStatement,负责对预处理方式进行sql分析，对于Statement的直接处理方式，不进行sql分析
 * 
 * @author akwei
 */
public class DALPreparedStatement implements PreparedStatement {

    private String sql = null;

    private DALConnection dalConnection = null;

    private PreparedStatement ps = null;

    private int autoGeneratedKeys = Statement.NO_GENERATED_KEYS;

    private int[] columnIndexes = null;

    private String[] columnNames = null;

    private int resultSetType = 0;

    private int resultSetConcurrency = 0;

    private int resultSetHoldability = 0;

    private int maxFieldSize = 0;

    private int maxRows = 0;

    private boolean escapeProcessing = true;

    private int queryTimeout = 0;

    private String cursorName = null;

    private int fetchDirection = 0;

    private int fetchSize = 0;

    private boolean poolable = true;

    private final DALParameters dalParameters = new DALParameters();

    private int createMethodByCon = 0;

    public static final int CREATE_METHOD_BY_CON_S = 1;

    public static final int CREATE_METHOD_BY_CON_S_I = 2;

    public static final int CREATE_METHOD_BY_CON_S_$I = 3;

    public static final int CREATE_METHOD_BY_CON_S_$S = 4;

    public static final int CREATE_METHOD_BY_CON_S_I_I = 5;

    public static final int CREATE_METHOD_BY_CON_S_I_I_I = 6;

    public void setCreateMethodByCon(int createMethodByCon) {
        this.createMethodByCon = createMethodByCon;
    }

    private void initRealPreparedStatement() throws SQLException {
        Connection con = this.dalConnection.getCurrentConnection();
        switch (this.createMethodByCon) {
            case CREATE_METHOD_BY_CON_S:
                ps = con.prepareStatement(sql);
                break;
            case CREATE_METHOD_BY_CON_S_I:
                ps = con.prepareStatement(sql, autoGeneratedKeys);
                break;
            case CREATE_METHOD_BY_CON_S_$I:
                ps = con.prepareStatement(sql, columnIndexes);
                break;
            case CREATE_METHOD_BY_CON_S_$S:
                ps = con.prepareStatement(sql, columnNames);
                break;
            case CREATE_METHOD_BY_CON_S_I_I:
                ps = con.prepareStatement(sql, resultSetType,
                        resultSetConcurrency);
                break;
            case CREATE_METHOD_BY_CON_S_I_I_I:
                ps = con.prepareStatement(sql, resultSetType,
                        resultSetConcurrency, resultSetHoldability);
                break;
        }
        if (ps == null) {
            throw new DALRunTimeException(
                    "can not create PreparedStatement for dsKey "
                            + DALCurrentStatus.getDsKey());
        }
    }

    /**
     * 初始化真正的PreparedStatement，对当前对象的操作全部都设置到真正的PreparedStatement
     * 
     * @throws SQLException
     */
    private void prepare() throws SQLException {
        DALCustomInfo dalCustomInfo = DALCurrentStatus.getCustomInfo();
        DALFactory dalFactory = DALFactory.getInstance();
        List<Object> values = dalParameters.getValues();
        SQLInfo sqlInfo = dalFactory.getSqlAnalyzer().analyse(sql,
                values.toArray(new Object[values.size()]));
        // 如果用户没有自定义设置，那么以解析结果为dsKey
        if (dalCustomInfo == null) {
            DALCurrentStatus.setDsKey(this.parsePartitionDsKey(sqlInfo));
        }
        this.sql = dalFactory.getSqlAnalyzer()
                .outPutSQL(sqlInfo, dalCustomInfo);
        this.initRealPreparedStatement();
        if (this.maxFieldSize != 0) {
            ps.setMaxFieldSize(maxFieldSize);
        }
        if (this.maxRows != 0) {
            ps.setMaxRows(maxRows);
        }
        if (!this.escapeProcessing) {
            ps.setEscapeProcessing(escapeProcessing);
        }
        if (this.queryTimeout != 0) {
            ps.setQueryTimeout(queryTimeout);
        }
        if (this.cursorName != null) {
            ps.setCursorName(cursorName);
        }
        if (this.fetchDirection != 0) {
            ps.setFetchDirection(fetchDirection);
        }
        if (this.fetchSize != 0) {
            ps.setFetchSize(fetchSize);
        }
        if (!this.poolable) {
            ps.setPoolable(poolable);
        }
        this.dalParameters.initRealPreparedStatement(ps);
    }

    private String parsePartitionDsKey(SQLInfo sqlInfo) throws SQLException {
        String[] tables = sqlInfo.getTables();
        PartitionParser parser;
        PartitionTableInfo[] infos = new PartitionTableInfo[tables.length];
        DALFactory dalFactory = DALFactory.getInstance();
        int i = 0;
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setAutoCommit(this.dalConnection.getAutoCommit());
        connectionStatus.setReadOnly(this.dalConnection.isReadOnly());
        for (String table : tables) {
            parser = dalFactory.getPartitionParserFactory().getParser(table);
            if (parser == null) {
                throw new PartitionParserNotFoundException(
                        "PartitionParser for table [" + table
                                + "] was not found");
            }
            infos[i] = parser.parse(table, sqlInfo, connectionStatus);
            if (infos[i] == null) {
                throw new ResultErrException(
                        "parser.parse must not  return null value");
            }
            sqlInfo.setRealTableName(table, infos[i].getRealTableName());
            i++;
        }
        String dsKey = null;
        // build partition dsKey
        for (PartitionTableInfo info : infos) {
            if (dsKey == null) {
                dsKey = info.getDsName();
            }
            else {
                if (!dsKey.equals(info.getDsName())) {
                    throw new MultDataSourceOnOperateException(
                            "mult datasource is not supported [" + dsKey
                                    + " , " + info.getDsName() + "]");
                }
            }
        }
        return dsKey;
    }

    private void prepare(String sql) throws SQLException {
        this.sql = sql;
        this.prepare();
    }

    private void assertPs() throws SQLException {
        if (ps == null) {
            throw new SQLException("no real PreparedStatement exist");
        }
    }

    private void reset() {
        this.sql = null;
        this.dalConnection = null;
        this.ps = null;
        this.autoGeneratedKeys = Statement.NO_GENERATED_KEYS;
        this.columnIndexes = null;
        this.columnNames = null;
        this.resultSetType = 0;
        this.resultSetConcurrency = 0;
        this.resultSetHoldability = 0;
        this.maxFieldSize = 0;
        this.maxRows = 0;
        this.escapeProcessing = true;
        this.queryTimeout = 0;
        this.cursorName = null;
        this.fetchDirection = 0;
        this.fetchSize = 0;
        this.poolable = true;
        this.dalParameters.clear();
    }

    public ResultSet executeQuery(String sql) throws SQLException {
        this.prepare(sql);
        return ps.executeQuery(sql);
    }

    public int executeUpdate(String sql) throws SQLException {
        this.prepare(sql);
        return ps.executeUpdate(sql);
    }

    public void close() throws SQLException {
        this.reset();
        if (ps != null) {
            ps.close();
        }
    }

    public int getMaxFieldSize() throws SQLException {
        return this.maxFieldSize;
    }

    public void setMaxFieldSize(int max) throws SQLException {
        this.maxFieldSize = max;
        if (ps != null) {
            ps.setMaxFieldSize(max);
        }
    }

    public int getMaxRows() throws SQLException {
        return this.maxRows;
    }

    public void setMaxRows(int max) throws SQLException {
        this.maxRows = max;
        if (ps != null) {
            ps.setMaxRows(max);
        }
    }

    public void setEscapeProcessing(boolean enable) throws SQLException {
        this.escapeProcessing = enable;
        if (ps != null) {
            ps.setEscapeProcessing(enable);
        }
    }

    public int getQueryTimeout() throws SQLException {
        return this.queryTimeout;
    }

    public void setQueryTimeout(int seconds) throws SQLException {
        this.queryTimeout = seconds;
        if (ps != null) {
            ps.setQueryTimeout(seconds);
        }
    }

    public void cancel() throws SQLException {
        if (ps != null) {
            ps.cancel();
        }
    }

    public SQLWarning getWarnings() throws SQLException {
        if (ps != null) {
            return ps.getWarnings();
        }
        return null;
    }

    public void clearWarnings() throws SQLException {
        if (ps != null) {
            ps.clearWarnings();
        }
    }

    public void setCursorName(String name) throws SQLException {
        this.cursorName = name;
        if (ps != null) {
            ps.setCursorName(name);
        }
    }

    public boolean execute(String sql) throws SQLException {
        this.prepare(sql);
        return ps.execute(sql);
    }

    public ResultSet getResultSet() throws SQLException {
        this.assertPs();
        return ps.getResultSet();
    }

    public int getUpdateCount() throws SQLException {
        this.assertPs();
        return ps.getUpdateCount();
    }

    public boolean getMoreResults() throws SQLException {
        this.assertPs();
        return ps.getMoreResults();
    }

    public void setFetchDirection(int direction) throws SQLException {
        this.fetchDirection = direction;
        if (ps != null) {
            ps.setFetchDirection(direction);
        }
    }

    public int getFetchDirection() throws SQLException {
        return this.fetchDirection;
    }

    public void setFetchSize(int rows) throws SQLException {
        this.fetchSize = rows;
        if (ps != null) {
            ps.setFetchSize(rows);
        }
    }

    public int getFetchSize() throws SQLException {
        return this.fetchSize;
    }

    public int getResultSetConcurrency() throws SQLException {
        return this.resultSetConcurrency;
    }

    public int getResultSetType() throws SQLException {
        return this.resultSetType;
    }

    public void addBatch(String sql) throws SQLException {
        throw new SQLException("do not support batch");
    }

    public void clearBatch() throws SQLException {
        this.prepare();
        ps.clearBatch();
    }

    public int[] executeBatch() throws SQLException {
        throw new SQLException("do not support batch");
    }

    public Connection getConnection() throws SQLException {
        return this.dalConnection;
    }

    public boolean getMoreResults(int current) throws SQLException {
        this.assertPs();
        return ps.getMoreResults(current);
    }

    public ResultSet getGeneratedKeys() throws SQLException {
        this.assertPs();
        return ps.getGeneratedKeys();
    }

    public int executeUpdate(String sql, int autoGeneratedKeys)
            throws SQLException {
        this.prepare(sql);
        return ps.executeUpdate(sql, autoGeneratedKeys);
    }

    public int executeUpdate(String sql, int[] columnIndexes)
            throws SQLException {
        this.prepare(sql);
        return ps.executeUpdate(sql, columnIndexes);
    }

    public int executeUpdate(String sql, String[] columnNames)
            throws SQLException {
        this.prepare(sql);
        return ps.executeUpdate(sql, columnNames);
    }

    public boolean execute(String sql, int autoGeneratedKeys)
            throws SQLException {
        this.prepare(sql);
        return ps.execute(sql, autoGeneratedKeys);
    }

    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        this.prepare(sql);
        return ps.execute(sql, columnIndexes);
    }

    public boolean execute(String sql, String[] columnNames)
            throws SQLException {
        this.prepare(sql);
        return ps.execute(sql, columnNames);
    }

    public int getResultSetHoldability() throws SQLException {
        return this.resultSetHoldability;
    }

    public boolean isClosed() throws SQLException {
        if (ps != null) {
            return ps.isClosed();
        }
        return true;
    }

    public void setPoolable(boolean poolable) throws SQLException {
        this.poolable = poolable;
        if (ps != null) {
            ps.setPoolable(poolable);
        }
    }

    public boolean isPoolable() throws SQLException {
        return this.poolable;
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        this.assertPs();
        return ps.unwrap(iface);
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        this.assertPs();
        return ps.isWrapperFor(iface);
    }

    public ResultSet executeQuery() throws SQLException {
        this.prepare();
        return ps.executeQuery();
    }

    public int executeUpdate() throws SQLException {
        this.prepare();
        return ps.executeUpdate();
    }

    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNULL_I_I, parameterIndex,
                new Object[] { sqlType });
    }

    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBOOLEAN_I_BOOL,
                parameterIndex, new Object[] { x });
    }

    public void setByte(int parameterIndex, byte x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBYTE_I_BYTE, parameterIndex,
                new Object[] { x });
    }

    public void setShort(int parameterIndex, short x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETSHORT_I_SHORT,
                parameterIndex, new Object[] { x });
    }

    public void setInt(int parameterIndex, int x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETINT_I_I, parameterIndex,
                new Object[] { x });
    }

    public void setLong(int parameterIndex, long x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETLONG_I_L, parameterIndex,
                new Object[] { x });
    }

    public void setFloat(int parameterIndex, float x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETFLOAT_I_F, parameterIndex,
                new Object[] { x });
    }

    public void setDouble(int parameterIndex, double x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETDOUBLE_I_D, parameterIndex,
                new Object[] { x });
    }

    public void setBigDecimal(int parameterIndex, BigDecimal x)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBIGDECIMAL_I_BIG,
                parameterIndex, new Object[] { x });
    }

    public void setString(int parameterIndex, String x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETSTRING_I_S, parameterIndex,
                new Object[] { x });
    }

    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBYTES_I_$BYTE,
                parameterIndex, new Object[] { x });
    }

    public void setDate(int parameterIndex, Date x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETDATE_I_DATE, parameterIndex,
                new Object[] { x });
    }

    public void setTime(int parameterIndex, Time x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETTIME_I_TIME, parameterIndex,
                new Object[] { x });
    }

    public void setTimestamp(int parameterIndex, Timestamp x)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETTIMESTAMP_I_TIMESTAMP,
                parameterIndex, new Object[] { x });
    }

    public void setAsciiStream(int parameterIndex, InputStream x, int length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETASCIISTREAM_I_IN_I,
                parameterIndex, new Object[] { x, length });
    }

    public void setUnicodeStream(int parameterIndex, InputStream x, int length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETUNICODESTREAM_I_IN_I,
                parameterIndex, new Object[] { x, length });
    }

    public void setBinaryStream(int parameterIndex, InputStream x, int length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBINARYSTREAM_I_IN_I,
                parameterIndex, new Object[] { x, length });
    }

    public void clearParameters() throws SQLException {
        this.dalParameters.clear();
        if (ps != null) {
            ps.clearParameters();
        }
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETOBJECTI_O_I, parameterIndex,
                new Object[] { x, targetSqlType });
    }

    public void setObject(int parameterIndex, Object x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETOBJECT_I_O, parameterIndex,
                new Object[] { x });
    }

    public boolean execute() throws SQLException {
        this.prepare();
        return ps.execute();
    }

    public void addBatch() throws SQLException {
        throw new SQLException("do not support batch");
    }

    public void setCharacterStream(int parameterIndex, Reader reader, int length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETCHARACTERSTREAM_I_READER_I,
                parameterIndex, new Object[] { reader, length });
    }

    public void setRef(int parameterIndex, Ref x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETREF_I_REF, parameterIndex,
                new Object[] { x });
    }

    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBLOB_I_BLOB, parameterIndex,
                new Object[] { x });
    }

    public void setClob(int parameterIndex, Clob x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETCLOB_I_CLOB, parameterIndex,
                new Object[] { x });
    }

    public void setArray(int parameterIndex, Array x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETARRAY_I_ARRAY,
                parameterIndex, new Object[] { x });
    }

    public ResultSetMetaData getMetaData() throws SQLException {
        this.assertPs();
        return ps.getMetaData();
    }

    public void setDate(int parameterIndex, Date x, Calendar cal)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETDATE_I_DATE_CAL,
                parameterIndex, new Object[] { x, cal });
    }

    public void setTime(int parameterIndex, Time x, Calendar cal)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETTIME_I_TIME_CAL,
                parameterIndex, new Object[] { x, cal });
    }

    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETTIMESTAMP_I_TIMESTAMP_CAL,
                parameterIndex, new Object[] { x, cal });
    }

    public void setNull(int parameterIndex, int sqlType, String typeName)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNULL_I_I_S, parameterIndex,
                new Object[] { sqlType, typeName });
    }

    public void setURL(int parameterIndex, URL x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETURL_I_URL, parameterIndex,
                new Object[] { x });
    }

    public ParameterMetaData getParameterMetaData() throws SQLException {
        this.assertPs();
        return ps.getParameterMetaData();
    }

    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETROWID_I_ROWID,
                parameterIndex, new Object[] { x });
    }

    public void setNString(int parameterIndex, String value)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNSTRING_I_S, parameterIndex,
                new Object[] { value });
    }

    public void setNCharacterStream(int parameterIndex, Reader value,
            long length) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNCHARACTERSTREAM_I_READER_L,
                parameterIndex, new Object[] { value, length });
    }

    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNCLOB_I_NCLOB,
                parameterIndex, new Object[] { value });
    }

    public void setClob(int parameterIndex, Reader reader, long length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETCLOB_I_READER_L,
                parameterIndex, new Object[] { reader, length });
    }

    public void setBlob(int parameterIndex, InputStream inputStream, long length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBLOB_I_IN_L, parameterIndex,
                new Object[] { inputStream, length });
    }

    public void setNClob(int parameterIndex, Reader reader, long length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNCLOB_I_READER_L,
                parameterIndex, new Object[] { reader, length });
    }

    public void setSQLXML(int parameterIndex, SQLXML xmlObject)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETSQLXML_I_SQLXML,
                parameterIndex, new Object[] { xmlObject });
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType,
            int scaleOrLength) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETOBJECT_I_O_I_I,
                parameterIndex,
                new Object[] { x, targetSqlType, scaleOrLength });
    }

    public void setAsciiStream(int parameterIndex, InputStream x, long length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETASCIISTREAM_I_IN_L,
                parameterIndex, new Object[] { x, length });
    }

    public void setBinaryStream(int parameterIndex, InputStream x, long length)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBINARYSTREAM_I_IN_L,
                parameterIndex, new Object[] { x, length });
    }

    public void setCharacterStream(int parameterIndex, Reader reader,
            long length) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETCHARACTERSTREAM_I_READER_L,
                parameterIndex, new Object[] { reader, length });
    }

    public void setAsciiStream(int parameterIndex, InputStream x)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETASCIISTREAM_I_IN,
                parameterIndex, new Object[] { x });
    }

    public void setBinaryStream(int parameterIndex, InputStream x)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBINARYSTREAM_I_IN,
                parameterIndex, new Object[] { x });
    }

    public void setCharacterStream(int parameterIndex, Reader reader)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETCHARACTERSTREAM_I_READER,
                parameterIndex, new Object[] { reader });
    }

    public void setNCharacterStream(int parameterIndex, Reader value)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNCHARACTERSTREAM_I_READER,
                parameterIndex, new Object[] { value });
    }

    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETCLOB_I_READER,
                parameterIndex, new Object[] { reader });
    }

    public void setBlob(int parameterIndex, InputStream inputStream)
            throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETBLOB_I_IN, parameterIndex,
                new Object[] { inputStream });
    }

    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        this.dalParameters.set(DALParameters.MN_SETNCLOB_I_READER,
                parameterIndex, new Object[] { reader });
    }

    public void setResultSetHoldability(int resultSetHoldability) {
        this.resultSetHoldability = resultSetHoldability;
    }

    public void setResultSetConcurrency(int resultSetConcurrency) {
        this.resultSetConcurrency = resultSetConcurrency;
    }

    public void setResultSetType(int resultSetType) {
        this.resultSetType = resultSetType;
    }

    public void setColumnIndexes(int[] columnIndexes) {
        this.columnIndexes = columnIndexes;
    }

    public void setColumnNames(String[] columnNames) {
        this.columnNames = columnNames;
    }

    public void setAutoGeneratedKeys(int autoGeneratedKeys) {
        this.autoGeneratedKeys = autoGeneratedKeys;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public void setDalConnection(DALConnection dalConnection) {
        this.dalConnection = dalConnection;
    }
}

package com.mawai.wiibcommon.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class BigDecimalArrayTypeHandler extends BaseTypeHandler<List<BigDecimal>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<BigDecimal> parameter, JdbcType jdbcType) throws SQLException {
        BigDecimal[] arr = parameter.toArray(new BigDecimal[0]);
        Array array = ps.getConnection().createArrayOf("numeric", arr);
        ps.setArray(i, array);
    }

    @Override
    public List<BigDecimal> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<BigDecimal> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<BigDecimal> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private List<BigDecimal> toList(Array array) throws SQLException {
        if (array == null) return null;
        Object[] raw = (Object[]) array.getArray();
        List<BigDecimal> result = new ArrayList<>(raw.length);
        for (Object o : raw) {
            result.add(o == null ? null : (o instanceof BigDecimal bd ? bd : new BigDecimal(o.toString())));
        }
        return result;
    }
}

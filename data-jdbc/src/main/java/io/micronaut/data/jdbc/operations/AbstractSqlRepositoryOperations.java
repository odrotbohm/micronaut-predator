package io.micronaut.data.jdbc.operations;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.data.annotation.*;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.intercept.annotation.DataMethod;
import io.micronaut.data.model.*;
import io.micronaut.data.model.query.builder.AbstractSqlLikeQueryBuilder;
import io.micronaut.data.model.query.builder.QueryBuilder;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.model.query.builder.sql.SqlQueryBuilder;
import io.micronaut.data.model.runtime.EntityOperation;
import io.micronaut.data.model.runtime.PreparedQuery;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;
import io.micronaut.data.model.runtime.RuntimePersistentProperty;
import io.micronaut.data.operations.RepositoryOperations;
import io.micronaut.data.runtime.config.DataSettings;
import io.micronaut.data.runtime.mapper.QueryStatement;
import io.micronaut.data.runtime.mapper.ResultReader;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract SQL repository implementation not specifically bound to JDBC.
 *
 * @param <RS> The result set type
 * @param <PS> The prepared statement type
 * @author graemerocher
 * @since 1.0.0
 */
public abstract class AbstractSqlRepositoryOperations<RS, PS> implements RepositoryOperations {
    protected static final SqlQueryBuilder DEFAULT_SQL_BUILDER = new SqlQueryBuilder();
    private static final Pattern IN_EXPRESSION_PATTERN = Pattern.compile("\\s\\?\\$IN\\((\\d+)\\)");
    private static final String NOT_TRUE_EXPRESSION = "1 = 2";
    @SuppressWarnings("WeakerAccess")
    protected final ResultReader<RS, String> columnNameResultSetReader;
    @SuppressWarnings("WeakerAccess")
    protected final ResultReader<RS, Integer> columnIndexResultSetReader;
    @SuppressWarnings("WeakerAccess")
    protected final QueryStatement<PS, Integer> preparedStatementWriter;
    protected final Map<Class, Dialect> dialects = new HashMap<>(10);
    protected final Map<Dialect, SqlQueryBuilder> queryBuilders = new HashMap<>(Dialect.values().length);

    private final Map<Class, StoredInsert> storedInserts = new ConcurrentHashMap<>(10);
    private final Map<Class, RuntimePersistentEntity> entities = new ConcurrentHashMap<>(10);
    private final Map<Class, RuntimePersistentProperty> idReaders = new ConcurrentHashMap<>(10);

    /**
     * Default constructor.
     *
     * @param columnNameResultSetReader  The column name result reader
     * @param columnIndexResultSetReader The column index result reader
     * @param preparedStatementWriter    The prepared statement writer
     */
    protected AbstractSqlRepositoryOperations(
            ResultReader<RS, String> columnNameResultSetReader,
            ResultReader<RS, Integer> columnIndexResultSetReader,
            QueryStatement<PS, Integer> preparedStatementWriter) {
        this.columnNameResultSetReader = columnNameResultSetReader;
        this.columnIndexResultSetReader = columnIndexResultSetReader;
        this.preparedStatementWriter = preparedStatementWriter;
    }

    @NonNull
    @Override
    public final <T> RuntimePersistentEntity<T> getEntity(@NonNull Class<T> type) {
        ArgumentUtils.requireNonNull("type", type);
        RuntimePersistentEntity<T> entity = entities.get(type);
        if (entity == null) {
            entity = new RuntimePersistentEntity<T>(type) {
                @Override
                protected RuntimePersistentEntity<T> getEntity(Class<T> type) {
                    return AbstractSqlRepositoryOperations.this.getEntity(type);
                }
            };
            entities.put(type, entity);
        }
        return entity;
    }

    /**
     * Sets the insert parameters for the given insert, entity and statement.
     *
     * @param insert The insert
     * @param entity The entity
     * @param stmt   The statement
     * @param <T>    The entity type
     */
    protected final <T> void setInsertParameters(@NonNull StoredInsert<T> insert, @NonNull T entity, @NonNull PS stmt) {
        Date now = null;
        RuntimePersistentEntity<T> persistentEntity = insert.getPersistentEntity();
        for (Map.Entry<String, Integer> entry : insert.getParameterBinding().entrySet()) {
            String propName = entry.getKey();
            RuntimePersistentProperty<T> prop = persistentEntity.getPropertyByName(propName);
            if (prop == null) {
                int i = propName.indexOf('.');
                if (i > -1) {
                    RuntimePersistentProperty embeddedProp = (RuntimePersistentProperty)
                            persistentEntity.getPropertyByPath(propName).orElse(null);
                    if (embeddedProp != null) {

                        // embedded case
                        prop = persistentEntity.getPropertyByName(propName.substring(0, i));
                        if (prop instanceof Association) {
                            Association assoc = (Association) prop;
                            if (assoc.getKind() == Relation.Kind.EMBEDDED) {

                                Object value = prop.getProperty().get(entity);
                                Object embeddedValue = embeddedProp.getProperty().get(value);
                                int index = entry.getValue();
                                preparedStatementWriter.setDynamic(
                                        stmt,
                                        index,
                                        embeddedProp.getDataType(),
                                        embeddedValue
                                );
                            }
                        }
                    }
                }
            } else {
                DataType type = prop.getDataType();
                BeanProperty<T, Object> beanProperty = (BeanProperty<T, Object>) prop.getProperty();
                Object value = beanProperty.get(entity);
                int index = entry.getValue();
                if (prop instanceof Association) {
                    Association association = (Association) prop;
                    if (!association.isForeignKey()) {
                        @SuppressWarnings("unchecked")
                        RuntimePersistentEntity<Object> associatedEntity = (RuntimePersistentEntity<Object>) association.getAssociatedEntity();
                        RuntimePersistentProperty<Object> identity = associatedEntity.getIdentity();
                        if (identity == null) {
                            throw new IllegalArgumentException("Associated entity has not ID: " + associatedEntity.getName());
                        } else {
                            type = identity.getDataType();
                        }
                        BeanProperty<Object, ?> identityProperty = identity.getProperty();
                        if (value != null) {
                            value = identityProperty.get(value);
                        }
                        if (DataSettings.QUERY_LOG.isTraceEnabled()) {
                            DataSettings.QUERY_LOG.trace("Binding value {} to parameter at position: {}", value, index);
                        }

                        preparedStatementWriter.setDynamic(
                                stmt,
                                index,
                                type,
                                value
                        );
                    }

                } else if (!prop.isGenerated()) {
                    if (beanProperty.hasStereotype(AutoPopulated.class)) {
                        if (beanProperty.hasAnnotation(DateCreated.class)) {
                            now = now != null ? now : new Date();
                            if (DataSettings.QUERY_LOG.isTraceEnabled()) {
                                DataSettings.QUERY_LOG.trace("Binding value {} to parameter at position: {}", now, index);
                            }
                            preparedStatementWriter.setDynamic(
                                    stmt,
                                    index,
                                    type,
                                    now
                            );
                            beanProperty.convertAndSet(entity, now);
                        } else if (beanProperty.hasAnnotation(DateUpdated.class)) {
                            now = now != null ? now : new Date();
                            if (DataSettings.QUERY_LOG.isTraceEnabled()) {
                                DataSettings.QUERY_LOG.trace("Binding value {} to parameter at position: {}", now, index);
                            }
                            preparedStatementWriter.setDynamic(
                                    stmt,
                                    index,
                                    type,
                                    now
                            );
                            beanProperty.convertAndSet(entity, now);
                        } else if (UUID.class.isAssignableFrom(beanProperty.getType())) {
                            UUID uuid = UUID.randomUUID();
                            if (DataSettings.QUERY_LOG.isTraceEnabled()) {
                                DataSettings.QUERY_LOG.trace("Binding value {} to parameter at position: {}", uuid, index);
                            }
                            preparedStatementWriter.setDynamic(
                                    stmt,
                                    index,
                                    type,
                                    uuid
                            );
                            beanProperty.set(entity, uuid);
                        } else {
                            throw new DataAccessException("Unsupported auto-populated annotation type: " + beanProperty.getAnnotationTypeByStereotype(AutoPopulated.class).orElse(null));
                        }
                    } else {
                        if (DataSettings.QUERY_LOG.isTraceEnabled()) {
                            DataSettings.QUERY_LOG.trace("Binding value {} to parameter at position: {}", value, index);
                        }
                        preparedStatementWriter.setDynamic(
                                stmt,
                                index,
                                type,
                                value
                        );
                    }
                }
            }

        }
    }

    /**
     * Resolve the INSERT for the given {@link EntityOperation}.
     *
     * @param operation The operation
     * @param <T>       The entity type
     * @return The insert
     */
    @NonNull
    protected final <T> StoredInsert resolveInsert(@NonNull EntityOperation<T> operation) {
        return storedInserts.computeIfAbsent(operation.getRootEntity(), aClass -> {
            AnnotationMetadata annotationMetadata = operation.getAnnotationMetadata();
            String insertStatement = annotationMetadata.stringValue(
                    DataMethod.class,
                    DataMethod.META_MEMBER_INSERT_STMT
            ).orElse(null);
            if (insertStatement == null) {
                throw new IllegalStateException("No insert statement present in repository. Ensure it extends GenericRepository and is annotated with @JdbcRepository");
            }

            RuntimePersistentEntity<T> persistentEntity = getEntity(operation.getRootEntity());
            Map<String, Integer> parameterBinding = buildSqlParameterBinding(annotationMetadata);
            // MSSQL doesn't support RETURN_GENERATED_KEYS https://github.com/Microsoft/mssql-jdbc/issues/245 with BATCHi
            boolean supportsBatch = annotationMetadata.findAnnotation(Repository.class)
                    .flatMap(av -> av.enumValue("dialect", Dialect.class)
                            .map(dialect -> dialect != Dialect.SQL_SERVER)).orElse(true);
            return new StoredInsert<>(insertStatement, persistentEntity, parameterBinding, supportsBatch);
        });
    }

    /**
     * Prepare the final query string for the given prepared query.
     *
     * @param preparedQuery   The prepared query
     * @param parameterValues The parameter values
     * @param isSingleResult  Is the result a single result
     * @param isUpdate        Is the query an update
     * @param <T>             The entity type
     * @param <R>             The result type
     * @return The query string
     */
    protected final @NonNull <T, R> String prepareQueryString(
            @NonNull PreparedQuery<T, R> preparedQuery,
            @NonNull Map<Integer, Object> parameterValues,
            boolean isSingleResult,
            boolean isUpdate) {
        String query = preparedQuery.getQuery();

        final boolean hasIn = preparedQuery.hasInExpression();
        if (hasIn) {
            query = expandInExpressions(query, parameterValues);
        }

        if (!isUpdate) {
            Pageable pageable = preparedQuery.getPageable();
            Class<T> rootEntity = preparedQuery.getRootEntity();
            if (pageable != Pageable.UNPAGED) {
                Sort sort = pageable.getSort();
                Dialect dialect = dialects.getOrDefault(preparedQuery.getRepositoryType(), Dialect.ANSI);
                QueryBuilder queryBuilder = queryBuilders.getOrDefault(dialect, DEFAULT_SQL_BUILDER);
                if (sort.isSorted()) {
                    query += queryBuilder.buildOrderBy(getEntity(rootEntity), sort).getQuery();
                } else if (isSqlServerWithoutOrderBy(query, dialect)) {
                    // SQL server requires order by
                    RuntimePersistentEntity<T> persistentEntity = getEntity(rootEntity);
                    sort = sortById(persistentEntity);
                    query += queryBuilder.buildOrderBy(persistentEntity, sort).getQuery();
                }
                if (isSingleResult && pageable.getOffset() > 0) {
                    pageable = Pageable.from(pageable.getNumber(), 1);
                }
                query += queryBuilder.buildPagination(pageable).getQuery();
            } else if (isSingleResult && preparedQuery.isSingleResult()) {
                Dialect dialect = dialects.getOrDefault(preparedQuery.getRepositoryType(), Dialect.ANSI);
                boolean isSqlServer = isSqlServerWithoutOrderBy(query, dialect);
                if (!isSqlServer || rootEntity == preparedQuery.getResultType()) {

                    QueryBuilder queryBuilder = queryBuilders.getOrDefault(dialect, DEFAULT_SQL_BUILDER);
                    if (isSqlServer) {
                        RuntimePersistentEntity<T> persistentEntity = getEntity(rootEntity);
                        Sort sort = sortById(persistentEntity);
                        query += queryBuilder.buildOrderBy(persistentEntity, sort).getQuery();
                    }
                    query += queryBuilder.buildPagination(Pageable.from(0, 1)).getQuery();
                }
            }
        }
        return query;
    }

    /**
     * Obtain an ID reader for the given object.
     *
     * @param o The object
     * @return The ID reader
     */
    @NonNull
    protected final RuntimePersistentProperty<Object> getIdReader(@NonNull Object o) {
        Class<Object> type = (Class<Object>) o.getClass();
        RuntimePersistentProperty beanProperty = idReaders.get(type);
        if (beanProperty == null) {

            RuntimePersistentEntity<Object> entity = getEntity(type);
            RuntimePersistentProperty<Object> identity = entity.getIdentity();
            if (identity == null) {
                throw new DataAccessException("Entity has no ID: " + entity.getName());
            }
            beanProperty = identity;
            idReaders.put(type, beanProperty);
        }
        return beanProperty;
    }

    /**
     * Bind the given prepared statement for the query and parameter values.
     *
     * @param preparedQuery   The prepared query
     * @param statement       The statement
     * @param parameterValues The parameter values
     * @param <T>             The entity type
     * @param <R>             The result type
     */
    protected final <T, R> void bindStatement(
            @NonNull PreparedQuery<T, R> preparedQuery,
            @NonNull PS statement,
            @NonNull Map<Integer, Object> parameterValues) {
        DataType[] parameterTypes = preparedQuery.getIndexedParameterTypes();
        int paramTypeLen = parameterTypes.length;
        for (Map.Entry<Integer, Object> entry : parameterValues.entrySet()) {
            int index = entry.getKey();
            Object value = entry.getValue();
            if (DataSettings.QUERY_LOG.isTraceEnabled()) {
                DataSettings.QUERY_LOG.trace("Binding parameter at position {} to value {}", index, value);
            }
            DataType dataType = paramTypeLen >= index ? parameterTypes[index - 1] : null;
            if (dataType == null) {
                dataType = DataType.OBJECT;
            }
            if (value == null) {
                setStatementParameter(statement, index, dataType, null);
            } else {
                if (value instanceof Iterable) {
                    Iterable i = (Iterable) value;
                    for (Object o : i) {
                        setStatementParameter(statement, index, dataType, o);
                        index++;
                    }
                } else if (value.getClass().isArray()) {
                    int len = Array.getLength(value);
                    for (int i = 0; i < len; i++) {
                        Object o = Array.get(value, i);
                        setStatementParameter(statement, index, dataType, o);
                        index++;
                    }
                } else {
                    setStatementParameter(statement, index, dataType, value);
                }
            }
        }
    }

    private <T> Map<String, Integer> buildSqlParameterBinding(AnnotationMetadata annotationMetadata) {
        AnnotationValue<DataMethod> annotation = annotationMetadata.getAnnotation(DataMethod.class);
        if (annotation == null) {
            return Collections.emptyMap();
        }
        List<AnnotationValue<Property>> parameterData = annotation.getAnnotations(DataMethod.META_MEMBER_INSERT_BINDING,
                Property.class);
        Map<String, Integer> parameterValues;
        if (CollectionUtils.isNotEmpty(parameterData)) {
            parameterValues = new HashMap<>(parameterData.size());
            for (AnnotationValue<Property> annotationValue : parameterData) {
                String name = annotationValue.stringValue("name").orElse(null);
                Integer argument = annotationValue.intValue("value").orElseThrow(() ->
                    new IllegalArgumentException("Query indices should be stored as integers")
                );
                if (name != null) {
                    parameterValues.put(name, argument);
                }
            }
        } else {
            parameterValues = Collections.emptyMap();
        }
        return parameterValues;
    }

    @NonNull
    private <T> Sort sortById(RuntimePersistentEntity<T> persistentEntity) {
        Sort sort;
        RuntimePersistentProperty<T> identity = persistentEntity.getIdentity();
        if (identity == null) {
            throw new DataAccessException("Pagination requires an entity ID on SQL Server");
        }
        sort = Sort.unsorted().order(Sort.Order.asc(identity.getName()));
        return sort;
    }

    private boolean isSqlServerWithoutOrderBy(String query, Dialect dialect) {
        return dialect == Dialect.SQL_SERVER && !query.contains(AbstractSqlLikeQueryBuilder.ORDER_BY_CLAUSE);
    }

    private int sizeOf(Object value) {
        if (value instanceof Collection) {
            return ((Collection) value).size();
        } else if (value instanceof Iterable) {
            int i = 0;
            for (Object ignored : ((Iterable) value)) {
                i++;
            }
            return i;
        } else if (value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return 1;
    }

    private String expandInExpressions(String query, Map<Integer, Object> parameterValues) {
        Set<Integer> indexes = parameterValues.keySet();
        Matcher matcher = IN_EXPRESSION_PATTERN.matcher(query);
        while (matcher.find()) {
            int inIndex = Integer.valueOf(matcher.group(1));
            Object value = parameterValues.get(inIndex);
            if (value == null) {
                query = matcher.replaceFirst(NOT_TRUE_EXPRESSION);
                parameterValues.remove(inIndex);
            } else {
                int size = sizeOf(value);
                if (size == 0) {
                    parameterValues.remove(inIndex);
                    query = matcher.replaceFirst(NOT_TRUE_EXPRESSION);
                } else {
                    String replacement = " IN(" + String.join(",", Collections.nCopies(size, "?")) + ")";
                    query = matcher.replaceFirst(replacement);
                    for (Integer index : indexes) {
                        if (index > inIndex) {
                            Object v = parameterValues.remove(index);
                            parameterValues.put(index + size, v);
                        }
                    }
                }
            }

            matcher = IN_EXPRESSION_PATTERN.matcher(query);

        }
        return query;
    }

    private void setStatementParameter(PS ps, int index, DataType dataType, Object o) {
        if (o != null) {
            if (dataType == DataType.ENTITY) {
                RuntimePersistentProperty<Object> idReader = getIdReader(o);
                Object id = idReader.getProperty().get(o);
                if (id == null) {
                    throw new DataAccessException("Supplied entity is a transient instance: " + o);
                }
                o = id;
                preparedStatementWriter.setDynamic(
                        ps,
                        index,
                        idReader.getDataType(),
                        o);
            } else {

                preparedStatementWriter.setDynamic(
                        ps,
                        index,
                        dataType,
                        o);
            }
        }
    }

    /**
     * A stored insert statement.
     *
     * @param <T> The entity type
     */
    protected final class StoredInsert<T> {
        private final Map<String, Integer> parameterBinding;
        private final RuntimePersistentProperty identity;
        private final boolean generateId;
        private final String sql;
        private final boolean supportsBatch;
        private final RuntimePersistentEntity<T> persistentEntity;

        /**
         * Default constructor.
         *
         * @param sql              The SQL INSERT
         * @param persistentEntity The entity
         * @param parameterBinding The parameter binding
         * @param supportsBatch    Whether batch insert is supported
         */
        StoredInsert(
                String sql,
                RuntimePersistentEntity<T> persistentEntity,
                Map<String, Integer> parameterBinding,
                boolean supportsBatch) {
            this.sql = sql;
            this.persistentEntity = persistentEntity;
            this.parameterBinding = parameterBinding;
            this.identity = persistentEntity.getIdentity();
            this.generateId = identity != null && identity.isGenerated();
            this.supportsBatch = supportsBatch;
        }

        /**
         * @return The persistent entity
         */
        public RuntimePersistentEntity<T> getPersistentEntity() {
            return persistentEntity;
        }

        /**
         * @return Whether batch inserts are allowed.
         */
        public boolean doesSupportBatch() {
            return supportsBatch;
        }

        /**
         * @return The SQL
         */
        public @NonNull
        String getSql() {
            return sql;
        }

        /**
         * @return The parameter binding
         */
        public @NonNull
        Map<String, Integer> getParameterBinding() {
            return parameterBinding;
        }

        /**
         * @return The identity
         */
        public @Nullable
        BeanProperty<T, Object> getIdentity() {
            if (identity != null) {
                return identity.getProperty();
            }
            return null;
        }

        /**
         * @return Is the id generated
         */
        public boolean isGenerateId() {
            return generateId;
        }
    }
}

// ============================================================================
//
// Copyright (C) 2006-2015 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.components.salesforce.runtime;

import static org.talend.components.salesforce.SalesforceOutputProperties.OutputAction.UPDATE;
import static org.talend.components.salesforce.SalesforceOutputProperties.OutputAction.UPSERT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.IndexedRecord;
import org.talend.components.api.component.runtime.WriteOperation;
import org.talend.components.api.component.runtime.Result;
import org.talend.components.api.component.runtime.WriterWithFeedback;
import org.talend.components.api.container.RuntimeContainer;
import org.talend.components.salesforce.tsalesforceoutput.TSalesforceOutputProperties;
import org.talend.daikon.avro.AvroUtils;
import org.talend.daikon.avro.SchemaConstants;
import org.talend.daikon.avro.converter.IndexedRecordConverter;

import com.sforce.soap.partner.*;
import com.sforce.soap.partner.Error;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.bind.CalendarCodec;
import com.sforce.ws.bind.DateCodec;
import com.sforce.ws.bind.XmlObject;
import com.sforce.ws.types.Time;

final class SalesforceWriter implements WriterWithFeedback<Result, IndexedRecord, IndexedRecord> {

    private final SalesforceWriteOperation salesforceWriteOperation;

    private PartnerConnection connection;

    private String uId;

    private final SalesforceSink sink;

    private final RuntimeContainer container;

    private final TSalesforceOutputProperties sprops;

    private String upsertKeyColumn;

    protected final List<IndexedRecord> deleteItems;

    protected final List<IndexedRecord> insertItems;

    protected final List<IndexedRecord> upsertItems;

    protected final List<IndexedRecord> updateItems;

    protected final int commitLevel;

    protected boolean exceptionForErrors;

    private int dataCount;

    private int successCount;

    private int rejectCount;

    private int deleteFieldId = -1;

    private transient IndexedRecordConverter<Object, ? extends IndexedRecord> factory;

    private transient Schema moduleSchema;

    private transient Schema mainSchema;

    private final List<IndexedRecord> successfulWrites = new ArrayList<>();

    private final List<IndexedRecord> rejectedWrites = new ArrayList<>();

    private final List<String> nullValueFields = new ArrayList<>();

    private CalendarCodec calendarCodec = new CalendarCodec();

    private DateCodec dateCodec = new DateCodec();

    public SalesforceWriter(SalesforceWriteOperation salesforceWriteOperation, RuntimeContainer container) {
        this.salesforceWriteOperation = salesforceWriteOperation;
        this.container = container;
        sink = salesforceWriteOperation.getSink();
        sprops = sink.getSalesforceOutputProperties();
        if (sprops.extendInsert.getValue()) {
            commitLevel = sprops.commitLevel.getValue();
        } else {
            commitLevel = 1;
        }
        int arraySize = commitLevel * 2;
        deleteItems = new ArrayList<>(arraySize);
        insertItems = new ArrayList<>(arraySize);
        updateItems = new ArrayList<>(arraySize);
        upsertItems = new ArrayList<>(arraySize);
        upsertKeyColumn = "";
        exceptionForErrors = sprops.ceaseForError.getValue();
    }

    @Override
    public void open(String uId) throws IOException {
        this.uId = uId;
        connection = sink.connect(container).connection;
        if (null == mainSchema) {
            mainSchema = sprops.module.main.schema.getValue();
            moduleSchema = sink.getSchema(connection, sprops.module.moduleName.getStringValue());
            if (AvroUtils.isIncludeAllFields(mainSchema)) {
                mainSchema = moduleSchema;
            } // else schema is fully specified
        }
        upsertKeyColumn = sprops.upsertKeyColumn.getStringValue();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void write(Object datum) throws IOException {
        dataCount++;
        // Ignore empty rows.
        if (null == datum) {
            return;
        }

        // This is all we need to do in order to ensure that we can process the incoming value as an IndexedRecord.
        if (null == factory) {
            factory = (IndexedRecordConverter<Object, ? extends IndexedRecord>) SalesforceAvroRegistry.get()
                    .createIndexedRecordConverter(datum.getClass());
        }
        IndexedRecord input = factory.convertToAvro(datum);

        // Clean the feedback records at each write.
        successfulWrites.clear();
        rejectedWrites.clear();

        switch (sprops.outputAction.getValue()) {
        case INSERT:
            insert(input);
            break;
        case UPDATE:
            update(input);
            break;
        case UPSERT:
            upsert(input);
            break;
        case DELETE:
            delete(input);
        }
    }

    private SObject createSObject(IndexedRecord input) {
        SObject so = new SObject();
        so.setType(sprops.module.moduleName.getStringValue());
        nullValueFields.clear();
        for (Schema.Field f : input.getSchema().getFields()) {
            Object value = input.get(f.pos());
            Schema.Field se = mainSchema.getField(f.name());
            if (se != null) {
                if (value != null && !value.toString().isEmpty()) {
                    addSObjectField(so, se.schema().getType(), se.name(), value);
                } else {
                    if (UPDATE.equals(sprops.outputAction.getValue())) {
                        nullValueFields.add(f.name());
                    }
                }
            }
        }
        if (!sprops.ignoreNull.getValue()) {
            so.setFieldsToNull(nullValueFields.toArray(new String[0]));
        }
        return so;
    }

    private SObject createSObjectForUpsert(IndexedRecord input) {
        SObject so = new SObject();
        so.setType(sprops.module.moduleName.getStringValue());
        Map<String, Map<String, String>> referenceFieldsMap = getReferenceFieldsMap();
        nullValueFields.clear();
        for (Schema.Field f : input.getSchema().getFields()) {
            Object value = input.get(f.pos());
            Schema.Field se = mainSchema.getField(f.name());
            if (value != null && !"".equals(value.toString()) && se != null) {
                if (referenceFieldsMap != null && referenceFieldsMap.get(se.name()) != null) {
                    Map<String, String> relationMap = referenceFieldsMap.get(se.name());
                    String lookupRelationshipFieldName = relationMap.get("lookupRelationshipFieldName");
                    so.setField(lookupRelationshipFieldName, null);
                    so.getChild(lookupRelationshipFieldName).setField("type", relationMap.get("lookupFieldModuleName"));
                    addSObjectField(so.getChild(lookupRelationshipFieldName), se.schema().getType(),
                            relationMap.get("lookupFieldExternalIdName"), value);
                } else {
                    addSObjectField(so, se.schema().getType(), se.name(), value);
                }
            } else {
                if (UPSERT.equals(sprops.outputAction.getValue()) && referenceFieldsMap != null
                        && referenceFieldsMap.get(se.name()) != null) {
                    Map<String, String> relationMap = referenceFieldsMap.get(se.name());
                    String lookupFieldName = relationMap.get("lookupFieldName");
                    if (lookupFieldName != null && !lookupFieldName.trim().isEmpty()) {
                        nullValueFields.add(lookupFieldName);
                    }
                } else if (se != null && !("Id".equals(se.name()) || se.name().equals(sprops.upsertKeyColumn.getValue()))) {
                    nullValueFields.add(se.name());
                }
            }
        }
        if (!sprops.ignoreNull.getValue()) {
            so.setFieldsToNull(nullValueFields.toArray(new String[0]));
        }
        return so;
    }

    private void addSObjectField(XmlObject xmlObject, Schema.Type expected, String fieldName, Object value) {
        Object valueToAdd = null;
        // Convert stuff here
        switch (expected) {
        case BYTES:
            valueToAdd = Charset.defaultCharset().decode(ByteBuffer.wrap((byte[]) value)).toString();
            break;
        default:
            valueToAdd = value;
            break;
        }
        if (valueToAdd instanceof Date) {
            xmlObject.setField(fieldName, SalesforceRuntime.convertDateToCalendar((Date) valueToAdd));
        } else {
            Schema.Field se = moduleSchema.getField(fieldName);
            if (se != null && valueToAdd instanceof String) {
                String datePattern = se.getProp(SchemaConstants.TALEND_COLUMN_PATTERN);
                if (datePattern != null && !datePattern.toString().isEmpty()) {
                    if ("yyyy-MM-dd'T'HH:mm:ss'.000Z'".equals(datePattern)) {
                        xmlObject.setField(fieldName, calendarCodec.deserialize((String) valueToAdd));
                    } else if ("yyyy-MM-dd".equals(datePattern)) {
                        xmlObject.setField(fieldName, dateCodec.deserialize((String) valueToAdd));
                    } else {
                        xmlObject.setField(fieldName, new Time((String) valueToAdd));
                    }
                } else {
                    xmlObject.setField(fieldName,
                            SalesforceAvroRegistry.get().getConverterFromString(se).convertToAvro((String) valueToAdd));
                }
            } else {
                xmlObject.setField(fieldName, valueToAdd);
            }
        }
    }

    private SaveResult[] insert(IndexedRecord input) throws IOException {
        insertItems.add(input);
        if (insertItems.size() >= commitLevel) {
            return doInsert();
        }
        return null;
    }

    private SaveResult[] doInsert() throws IOException {
        if (insertItems.size() > 0) {
            SObject[] accs = new SObject[insertItems.size()];
            for (int i = 0; i < insertItems.size(); i++)
                accs[i] = createSObject(insertItems.get(i));

            String[] changedItemKeys = new String[accs.length];
            SaveResult[] saveResults;
            try {
                saveResults = connection.create(accs);
                if (saveResults != null && saveResults.length != 0) {
                    int batch_idx = -1;
                    for (int i = 0; i < saveResults.length; i++) {
                        if (saveResults[i].getSuccess())
                            handleSuccess(insertItems.get(i), saveResults[i].getId());
                        else
                            handleReject(insertItems.get(i), saveResults[i].getErrors(), changedItemKeys, ++batch_idx);
                    }
                }
                insertItems.clear();
                return saveResults;
            } catch (ConnectionException e) {
                throw new IOException(e);
            }
        }
        return null;
    }

    private SaveResult[] update(IndexedRecord input) throws IOException {
        updateItems.add(input);
        if (updateItems.size() >= commitLevel) {
            return doUpdate();
        }
        return null;
    }

    private SaveResult[] doUpdate() throws IOException {
        if (updateItems.size() > 0) {
            SObject[] upds = new SObject[updateItems.size()];
            for (int i = 0; i < updateItems.size(); i++)
                upds[i] = createSObject(updateItems.get(i));

            String[] changedItemKeys = new String[upds.length];
            for (int ix = 0; ix < upds.length; ++ix) {
                changedItemKeys[ix] = upds[ix].getId();
            }
            SaveResult[] saveResults;
            try {
                saveResults = connection.update(upds);
                upds = null;
                if (saveResults != null && saveResults.length != 0) {
                    int batch_idx = -1;
                    for (int i = 0; i < saveResults.length; i++) {
                        if (saveResults[i].getSuccess())
                            handleSuccess(updateItems.get(i), saveResults[i].getId());
                        else
                            handleReject(updateItems.get(i), saveResults[i].getErrors(), changedItemKeys, ++batch_idx);
                    }
                }
                updateItems.clear();
                return saveResults;
            } catch (ConnectionException e) {
                throw new IOException(e);
            }
        }
        return null;
    }

    private UpsertResult[] upsert(IndexedRecord input) throws IOException {
        upsertItems.add(input);
        if (upsertItems.size() >= commitLevel) {
            return doUpsert();
        }
        return null;
    }

    private UpsertResult[] doUpsert() throws IOException {
        if (upsertItems.size() > 0) {
            SObject[] upds = new SObject[upsertItems.size()];
            for (int i = 0; i < upsertItems.size(); i++)
                upds[i] = createSObjectForUpsert(upsertItems.get(i));

            String[] changedItemKeys = new String[upds.length];
            for (int ix = 0; ix < upds.length; ++ix) {
                Object value = upds[ix].getField(upsertKeyColumn);
                if (value == null) {
                    changedItemKeys[ix] = "No value for " + upsertKeyColumn + " ";
                } else {
                    changedItemKeys[ix] = upsertKeyColumn;
                }
            }
            UpsertResult[] upsertResults;
            try {
                upsertResults = connection.upsert(upsertKeyColumn, upds);
                upds = null;
                if (upsertResults != null && upsertResults.length != 0) {
                    int batch_idx = -1;
                    for (int i = 0; i < upsertResults.length; i++) {
                        if (upsertResults[i].getSuccess())
                            handleSuccess(upsertItems.get(i), upsertResults[i].getId());
                        else
                            handleReject(upsertItems.get(0), upsertResults[i].getErrors(), changedItemKeys, ++batch_idx);
                    }
                }
                upsertItems.clear();
                return upsertResults;
            } catch (ConnectionException e) {
                throw new IOException(e);
            }
        }
        return null;

    }

    private void handleSuccess(IndexedRecord input, String id) {
        successCount++;
        Schema outSchema = sprops.schemaFlow.schema.getValue();
        if (outSchema == null || outSchema.getFields().size() == 0)
            return;
        if (input.getSchema().equals(outSchema)) {
            successfulWrites.add(input);
        } else {
            IndexedRecord successful = new GenericData.Record(outSchema);
            for (Schema.Field outField : successful.getSchema().getFields()) {
                Object outValue = null;
                Schema.Field inField = input.getSchema().getField(outField.name());
                if (inField != null)
                    outValue = input.get(inField.pos());
                else if (TSalesforceOutputProperties.FIELD_SALESFORCE_ID.equals(outField.name()))
                    outValue = id;
                successful.put(outField.pos(), outValue);
            }
            successfulWrites.add(successful);
        }
    }

    private void handleReject(IndexedRecord input, Error[] resultErrors, String[] changedItemKeys, int batchIdx)
            throws IOException {
        if (exceptionForErrors) {
            StringBuilder errors = SalesforceRuntime.addLog(resultErrors,
                    batchIdx < changedItemKeys.length ? changedItemKeys[batchIdx] : "Batch index out of bounds", null);
            if (errors.toString().length() > 0) {
                throw new IOException(errors.toString());
            }
        } else {
            rejectCount++;
            Schema outSchema = sprops.schemaReject.schema.getValue();
            if (outSchema == null || outSchema.getFields().size() == 0)
                return;
            if (input.getSchema().equals(outSchema)) {
                rejectedWrites.add(input);
            } else {
                IndexedRecord reject = new GenericData.Record(outSchema);
                for (Schema.Field outField : reject.getSchema().getFields()) {
                    Object outValue = null;
                    Schema.Field inField = input.getSchema().getField(outField.name());
                    if (inField != null)
                        outValue = input.get(inField.pos());
                    else if (resultErrors.length > 0) {
                        Error error = resultErrors[0];
                        if (TSalesforceOutputProperties.FIELD_ERROR_CODE.equals(outField.name()))
                            outValue = error.getStatusCode() != null ? error.getStatusCode().toString() : null;
                        else if (TSalesforceOutputProperties.FIELD_ERROR_FIELDS.equals(outField.name())) {
                            StringBuffer fields = new StringBuffer();
                            for (String field : error.getFields()) {
                                fields.append(field);
                                fields.append(",");
                            }
                            if (fields.length() > 0) {
                                fields.deleteCharAt(fields.length() - 1);
                            }
                            outValue = fields.toString();
                        } else if (TSalesforceOutputProperties.FIELD_ERROR_MESSAGE.equals(outField.name()))
                            outValue = error.getMessage();
                    }
                    reject.put(outField.pos(), outValue);
                }
                rejectedWrites.add(reject);
            }
        }
    }

    private DeleteResult[] delete(IndexedRecord input) throws IOException {
        // Calculate the field position of the Id the first time that it is used. The Id field must be present in the
        // schema to delete rows.
        if (deleteFieldId == -1) {
            String ID = "Id";
            Schema.Field idField = input.getSchema().getField(ID);
            if (null == idField)
                throw new RuntimeException(ID + " not found");
            deleteFieldId = idField.pos();
        }
        String id = (String) input.get(deleteFieldId);
        if (id != null) {
            deleteItems.add(input);
            if (deleteItems.size() >= commitLevel) {
                return doDelete();
            }
        }
        return null;
    }

    private DeleteResult[] doDelete() throws IOException {
        if (deleteItems.size() > 0) {
            String[] delIDs = new String[deleteItems.size()];
            String[] changedItemKeys = new String[delIDs.length];
            for (int ix = 0; ix < delIDs.length; ++ix) {
                delIDs[ix] = (String) deleteItems.get(ix).get(deleteFieldId);
                changedItemKeys[ix] = delIDs[ix];
            }
            DeleteResult[] dr;
            try {
                dr = connection.delete(delIDs);
                if (dr != null && dr.length != 0) {
                    int batch_idx = -1;
                    for (int i = 0; i < dr.length; i++) {
                        if (dr[i].getSuccess())
                            handleSuccess(deleteItems.get(i), dr[i].getId());
                        else
                            handleReject(deleteItems.get(i), dr[i].getErrors(), changedItemKeys, ++batch_idx);
                    }
                }
                deleteItems.clear();
                return dr;
            } catch (ConnectionException e) {
                throw new IOException(e);
            }
        }
        return null;
    }

    @Override
    public Result close() throws IOException {
        logout();
        return new Result(uId, dataCount, successCount, rejectCount);
    }

    private void logout() throws IOException {
        // Finish anything uncommitted
        doInsert();
        doDelete();
        doUpdate();
        doUpsert();
    }

    @Override
    public WriteOperation<Result> getWriteOperation() {
        return salesforceWriteOperation;
    }

    private Map<String, Map<String, String>> getReferenceFieldsMap() {
        Object columns = sprops.upsertRelationTable.columnName.getValue();
        Map<String, Map<String, String>> referenceFieldsMap = null;
        if (columns != null && columns instanceof List) {
            referenceFieldsMap = new HashMap<>();
            List<String> lookupFieldModuleNames = sprops.upsertRelationTable.lookupFieldModuleName.getValue();
            List<String> lookupFieldNames = sprops.upsertRelationTable.lookupFieldName.getValue();
            List<String> lookupRelationshipFieldNames = sprops.upsertRelationTable.lookupRelationshipFieldName.getValue();
            List<String> externalIdFromLookupFields = sprops.upsertRelationTable.lookupFieldExternalIdName.getValue();
            for (int index = 0; index < ((List) columns).size(); index++) {
                Map<String, String> relationMap = new HashMap<>();
                relationMap.put("lookupFieldModuleName", lookupFieldModuleNames.get(index));
                if (sprops.upsertRelationTable.isUseLookupFieldName() && lookupFieldNames != null) {
                    relationMap.put("lookupFieldName", lookupFieldNames.get(index));
                }
                relationMap.put("lookupRelationshipFieldName", lookupRelationshipFieldNames.get(index));
                relationMap.put("lookupFieldExternalIdName", externalIdFromLookupFields.get(index));
                referenceFieldsMap.put(((List<String>) columns).get(index), relationMap);
            }
        }
        return referenceFieldsMap;
    }

    @Override
    public List<IndexedRecord> getSuccessfulWrites() {
        return Collections.unmodifiableList(successfulWrites);
    }

    @Override
    public List<IndexedRecord> getRejectedWrites() {
        return Collections.unmodifiableList(rejectedWrites);
    }
}

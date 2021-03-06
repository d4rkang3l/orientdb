/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.core.record.impl;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.util.OCommonConst;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.*;
import com.orientechnologies.orient.core.db.record.ridbag.ORidBag;
import com.orientechnologies.orient.core.exception.*;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.iterator.OEmptyMapEntryIterator;
import com.orientechnologies.orient.core.metadata.OMetadataInternal;
import com.orientechnologies.orient.core.metadata.schema.*;
import com.orientechnologies.orient.core.metadata.security.OIdentity;
import com.orientechnologies.orient.core.metadata.security.OSecurityShared;
import com.orientechnologies.orient.core.record.*;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.serialization.serializer.record.ORecordSerializer;
import com.orientechnologies.orient.core.serialization.serializer.record.ORecordSerializerFactory;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.ORecordSerializerNetwork;
import com.orientechnologies.orient.core.sql.OSQLHelper;
import com.orientechnologies.orient.core.sql.filter.OSQLPredicate;
import com.orientechnologies.orient.core.storage.OBasicTransaction;
import com.orientechnologies.orient.core.storage.OStorage;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Document representation to handle values dynamically. Can be used in schema-less, schema-mixed and schema-full modes. Fields can
 * be added at run-time. Instances can be reused across calls by using the reset() before to re-use.
 */
@SuppressWarnings({ "unchecked" })
public class ODocument extends ORecordAbstract
    implements Iterable<Entry<String, Object>>, ORecordSchemaAware, ODetachable, Externalizable, OElement {

  public static final    byte     RECORD_TYPE      = 'd';
  protected static final String[] EMPTY_STRINGS    = new String[] {};
  private static final   long     serialVersionUID = 1L;
  protected int _fieldSize;

  protected Map<String, ODocumentEntry> _fields;

  protected           boolean                             _trackingChanges    = true;
  protected           boolean                             _ordered            = true;
  protected           boolean                             _lazyLoad           = true;
  protected           boolean                             _allowChainedAccess = true;
  protected transient List<WeakReference<ORecordElement>> _owners             = null;
  protected OImmutableSchema _schema;
  private   String           _className;
  private   OImmutableClass  _immutableClazz;
  private int _immutableSchemaVersion = 1;

  /**
   * Internal constructor used on unmarshalling.
   */
  public ODocument() {
    setup();
  }

  /**
   * Creates a new instance by the raw stream usually read from the database. New instances are not persistent until {@link #save()}
   * is called.
   *
   * @param iSource Raw stream
   */
  public ODocument(final byte[] iSource) {
    _source = iSource;
    setup();
  }

  /**
   * Creates a new instance by the raw stream usually read from the database. New instances are not persistent until {@link #save()}
   * is called.
   *
   * @param iSource Raw stream as InputStream
   */
  public ODocument(final InputStream iSource) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    OIOUtils.copyStream(iSource, out, -1);
    _source = out.toByteArray();
    setup();
  }

  /**
   * Creates a new instance in memory linked by the Record Id to the persistent one. New instances are not persistent until
   * {@link #save()} is called.
   *
   * @param iRID Record Id
   */
  public ODocument(final ORID iRID) {
    setup();
    _recordId = (ORecordId) iRID;
    _status = STATUS.NOT_LOADED;
    _dirty = false;
    _contentChanged = false;
  }

  /**
   * Creates a new instance in memory of the specified class, linked by the Record Id to the persistent one. New instances are not
   * persistent until {@link #save()} is called.
   *
   * @param iClassName Class name
   * @param iRID       Record Id
   */
  public ODocument(final String iClassName, final ORID iRID) {
    this(iClassName);
    _recordId = (ORecordId) iRID;

    final ODatabaseDocumentInternal database = getDatabaseInternal();
    if (_recordId.getClusterId() > -1) {
      final OSchema schema = database.getMetadata().getImmutableSchemaSnapshot();
      final OClass cls = schema.getClassByClusterId(_recordId.getClusterId());
      if (cls != null && !cls.getName().equals(iClassName))
        throw new IllegalArgumentException(
            "Cluster id does not correspond class name should be " + iClassName + " but found " + cls.getName());
    }

    _dirty = false;
    _contentChanged = false;
    _status = STATUS.NOT_LOADED;
  }

  /**
   * Creates a new instance in memory of the specified class. New instances are not persistent until {@link #save()} is called.
   *
   * @param iClassName Class name
   */
  public ODocument(final String iClassName) {
    setup();
    setClassName(iClassName);
  }

  /**
   * Creates a new instance in memory of the specified schema class. New instances are not persistent until {@link #save()} is
   * called. The database reference is taken from the thread local.
   *
   * @param iClass OClass instance
   */
  public ODocument(final OClass iClass) {
    this(iClass != null ? iClass.getName() : null);
  }

  /**
   * Fills a document passing the field array in form of pairs of field name and value.
   *
   * @param iFields Array of field pairs
   */
  public ODocument(final Object[] iFields) {
    setup();
    if (iFields != null && iFields.length > 0)
      for (int i = 0; i < iFields.length; i += 2) {
        field(iFields[i].toString(), iFields[i + 1]);
      }
  }

  /**
   * Fills a document passing a map of key/values where the key is the field name and the value the field's value.
   *
   * @param iFieldMap Map of Object/Object
   */
  public ODocument(final Map<?, Object> iFieldMap) {
    setup();
    if (iFieldMap != null && !iFieldMap.isEmpty())
      for (Entry<?, Object> entry : iFieldMap.entrySet()) {
        field(entry.getKey().toString(), entry.getValue());
      }
  }

  /**
   * Fills a document passing the field names/values pair, where the first pair is mandatory.
   */
  public ODocument(final String iFieldName, final Object iFieldValue, final Object... iFields) {
    this(iFields);
    field(iFieldName, iFieldValue);
  }

  @Override
  public Optional<OVertex> asVertex() {
    if (this instanceof OVertex)
      return Optional.of((OVertex) this);
    OClass type = this.getSchemaClass();
    if (type == null) {
      return Optional.empty();
    }
    if (type.isVertexType()) {
      return Optional.of(new OVertexDelegate(this));
    }
    return Optional.empty();
  }

  @Override
  public Optional<OEdge> asEdge() {
    if (this instanceof OVertex)
      return Optional.of((OEdge) this);
    OClass type = this.getSchemaClass();
    if (type == null) {
      return Optional.empty();
    }
    if (type.isEdgeType()) {
      return Optional.of(new OEdgeDelegate(this));
    }
    return Optional.empty();
  }

  @Override
  public boolean isVertex() {
    if (this instanceof OVertex)
      return true;
    OClass type = this.getSchemaClass();
    if (type == null) {
      return false;
    }
    return type.isVertexType();
  }

  @Override
  public boolean isEdge() {
    if (this instanceof OEdge)
      return true;
    OClass type = this.getSchemaClass();
    if (type == null) {
      return false;
    }
    return type.isEdgeType();
  }

  @Override
  public Optional<OClass> getSchemaType() {
    return Optional.ofNullable(getSchemaClass());
  }

  @Override
  public Set<String> getPropertyNames() {
    checkForLoading();

    if (_status == ORecordElement.STATUS.LOADED && _source != null && ODatabaseRecordThreadLocal.INSTANCE.isDefined()
        && !ODatabaseRecordThreadLocal.INSTANCE.get().isClosed()) {
      // DESERIALIZE FIELD NAMES ONLY (SUPPORTED ONLY BY BINARY SERIALIZER)
      final String[] fieldNames = _recordFormat.getFieldNames(this, _source);
      if (fieldNames != null) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, fieldNames);
        return result;
      }
    }

    checkForFields();

    if (_fields == null || _fields.size() == 0)
      return Collections.EMPTY_SET;

    return _fields.entrySet().stream().filter(s -> s.getValue().exist()).map(Entry::getKey).collect(Collectors.toSet());
  }

  /**
   * retrieves a property value from the current document
   *
   * @param iFieldName The field name, it can contain any character (it's not evaluated as an expression, as in #eval()
   * @param <RET>
   *
   * @return the field value. Null if the field does not exist.
   */
  public <RET> RET getProperty(final String iFieldName) {
    if (iFieldName == null)
      return null;

    checkForLoading();
    RET value = (RET) ODocumentHelper.getIdentifiableValue(this, iFieldName);

    if (!iFieldName.startsWith("@") && _lazyLoad && value instanceof ORID && (((ORID) value).isPersistent() || ((ORID) value)
        .isNew()) && ODatabaseRecordThreadLocal.INSTANCE.isDefined()) {
      // CREATE THE DOCUMENT OBJECT IN LAZY WAY
      RET newValue = getDatabase().load((ORID) value);
      if (newValue != null) {
        unTrack((ORID) value);
        track((OIdentifiable) newValue);
        value = newValue;
        if (isTrackingChanges()) {
          ORecordInternal.setDirtyManager((ORecord) value, this.getDirtyManager());
        }
        ODocumentEntry entry = _fields.get(iFieldName);
        removeCollectionChangeListener(entry, entry.value);
        entry.value = value;
        addCollectionChangeListener(entry);
      }
    }

    return value;
  }

  /**
   * retrieves a property value from the current document, without evaluating it (eg. no conversion from RID to document)
   *
   * @param iFieldName The field name, it can contain any character (it's not evaluated as an expression, as in #eval()
   * @param <RET>
   *
   * @return the field value. Null if the field does not exist.
   */
  protected <RET> RET getRawProperty(final String iFieldName) {
    if (iFieldName == null)
      return null;

    checkForLoading();
    return (RET) ODocumentHelper.getIdentifiableValue(this, iFieldName);
  }

  /**
   * sets a property value on current document
   *
   * @param iFieldName     The property name
   * @param iPropertyValue The property value
   */
  public void setProperty(final String iFieldName, Object iPropertyValue) {
    setProperty(iFieldName, iPropertyValue, OCommonConst.EMPTY_TYPES_ARRAY);
  }

  /**
   * Sets
   *
   * @param iPropetyName   The property name
   * @param iPropertyValue The property value
   * @param iFieldType     Forced type (not auto-determined)
   */
  public void setProperty(String iPropetyName, Object iPropertyValue, OType... iFieldType) {
    if (iPropetyName == null)
      throw new IllegalArgumentException("Field is null");

    if (iPropetyName.isEmpty())
      throw new IllegalArgumentException("Field name is empty");

    if (ODocumentHelper.ATTRIBUTE_CLASS.equals(iPropetyName)) {
      setClassName(iPropertyValue.toString());
      return;
    } else if (ODocumentHelper.ATTRIBUTE_RID.equals(iPropetyName)) {
      _recordId.fromString(iPropertyValue.toString());
      return;
    } else if (ODocumentHelper.ATTRIBUTE_VERSION.equals(iPropetyName)) {
      if (iPropertyValue != null) {
        int v;

        if (iPropertyValue instanceof Number)
          v = ((Number) iPropertyValue).intValue();
        else
          v = Integer.parseInt(iPropertyValue.toString());

        _recordVersion = v;
      }
      return;
    }

    checkForLoading();
    checkForFields();

    ODocumentEntry entry = _fields.get(iPropetyName);
    final boolean knownProperty;
    final Object oldValue;
    final OType oldType;
    if (entry == null) {
      entry = new ODocumentEntry();
      _fieldSize++;
      _fields.put(iPropetyName, entry);
      entry.setCreated(true);
      knownProperty = false;
      oldValue = null;
      oldType = null;
    } else {
      knownProperty = entry.exist();
      oldValue = entry.value;
      oldType = entry.type;
    }
    OType fieldType = deriveFieldType(iPropetyName, entry, iFieldType);
    if (iPropertyValue != null && fieldType != null) {
      iPropertyValue = ODocumentHelper.convertField(this, iPropetyName, fieldType, null, iPropertyValue);
    } else if (iPropertyValue instanceof Enum)
      iPropertyValue = iPropertyValue.toString();

    if (knownProperty)
      // CHECK IF IS REALLY CHANGED
      if (iPropertyValue == null) {
        if (oldValue == null)
          // BOTH NULL: UNCHANGED
          return;
      } else {

        try {
          if (iPropertyValue.equals(oldValue)) {
            if (fieldType == oldType) {
              if (!(iPropertyValue instanceof ORecordElement))
                // SAME BUT NOT TRACKABLE: SET THE RECORD AS DIRTY TO BE SURE IT'S SAVED
                setDirty();

              // SAVE VALUE: UNCHANGED
              return;
            }
          }
        } catch (Exception e) {
          OLogManager.instance()
              .warn(this, "Error on checking the value of property %s against the record %s", e, iPropetyName, getIdentity());
        }
      }

    if (oldValue instanceof ORidBag) {
      final ORidBag ridBag = (ORidBag) oldValue;
      ridBag.setOwner(null);
    } else if (oldValue instanceof ODocument) {
      ((ODocument) oldValue).removeOwner(this);
    }

    if (oldValue instanceof OIdentifiable) {
      unTrack((OIdentifiable) oldValue);
    }

    if (iPropertyValue != null) {
      if (iPropertyValue instanceof ODocument) {
        if (OType.EMBEDDED.equals(fieldType)) {
          final ODocument embeddedDocument = (ODocument) iPropertyValue;
          ODocumentInternal.addOwner(embeddedDocument, this);
        }
      }
      if (iPropertyValue instanceof OIdentifiable) {
        track((OIdentifiable) iPropertyValue);
      }

      if (iPropertyValue instanceof ORidBag) {
        final ORidBag ridBag = (ORidBag) iPropertyValue;
        ridBag.setOwner(null); // in order to avoid IllegalStateException when ridBag changes the owner (ODocument.merge)
        ridBag.setOwner(this);
      }
    }

    if (oldType != fieldType && oldType != null) {
      // can be made in a better way, but "keeping type" issue should be solved before
      if (iPropertyValue == null || fieldType != null || oldType != OType.getTypeByValue(iPropertyValue))
        entry.type = fieldType;
    }
    removeCollectionChangeListener(entry, oldValue);
    entry.value = iPropertyValue;
    if (!entry.exist()) {
      entry.setExist(true);
      _fieldSize++;
    }
    addCollectionChangeListener(entry);

    if (_status != STATUS.UNMARSHALLING) {
      setDirty();
      if (!entry.isChanged()) {
        entry.original = oldValue;
        entry.setChanged(true);
      }
    }
  }

  public <RET> RET removeProperty(final String iFieldName) {
    checkForLoading();
    checkForFields();

    if (ODocumentHelper.ATTRIBUTE_CLASS.equalsIgnoreCase(iFieldName)) {
      setClassName(null);
    } else if (ODocumentHelper.ATTRIBUTE_RID.equalsIgnoreCase(iFieldName)) {
      _recordId = new ORecordId();
    }

    final ODocumentEntry entry = _fields.get(iFieldName);
    if (entry == null)
      return null;
    Object oldValue = entry.value;
    if (entry.exist() && _trackingChanges) {
      // SAVE THE OLD VALUE IN A SEPARATE MAP
      if (entry.original == null)
        entry.original = entry.value;
      entry.value = null;
      entry.setExist(false);
      entry.setChanged(true);
    } else {
      _fields.remove(iFieldName);
    }
    _fieldSize--;

    removeCollectionChangeListener(entry, oldValue);
    if (oldValue instanceof OIdentifiable)
      unTrack((OIdentifiable) oldValue);
    if (oldValue instanceof ORidBag)
      ((ORidBag) oldValue).setOwner(null);
    setDirty();
    return (RET) oldValue;
  }

  protected static void validateField(ODocument iRecord, OImmutableProperty p) throws OValidationException {
    final Object fieldValue;
    ODocumentEntry entry = iRecord._fields.get(p.getName());
    if (entry != null && entry.exist()) {
      // AVOID CONVERSIONS: FASTER!
      fieldValue = entry.value;

      if (p.isNotNull() && fieldValue == null)
        // NULLITY
        throw new OValidationException("The field '" + p.getFullName() + "' cannot be null, record: " + iRecord);

      if (fieldValue != null && p.getRegexp() != null && p.getType().equals(OType.STRING)) {
        // REGEXP
        if (!((String) fieldValue).matches(p.getRegexp()))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' does not match the regular expression '" + p.getRegexp() + "'. Field value is: "
                  + fieldValue + ", record: " + iRecord);
      }

    } else {
      if (p.isMandatory()) {
        throw new OValidationException("The field '" + p.getFullName() + "' is mandatory, but not found on record: " + iRecord);
      }
      fieldValue = null;
    }

    final OType type = p.getType();

    if (fieldValue != null && type != null) {
      // CHECK TYPE
      switch (type) {
      case LINK:
        validateLink(p, fieldValue, false);
        break;
      case LINKLIST:
        if (!(fieldValue instanceof List))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as LINKLIST but an incompatible type is used. Value: "
                  + fieldValue);
        validateLinkCollection(p, (Collection<Object>) fieldValue, entry);
        break;
      case LINKSET:
        if (!(fieldValue instanceof Set))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as LINKSET but an incompatible type is used. Value: "
                  + fieldValue);
        validateLinkCollection(p, (Collection<Object>) fieldValue, entry);
        break;
      case LINKMAP:
        if (!(fieldValue instanceof Map))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as LINKMAP but an incompatible type is used. Value: "
                  + fieldValue);
        validateLinkCollection(p, ((Map<?, Object>) fieldValue).values(), entry);
        break;

      case LINKBAG:
        if (!(fieldValue instanceof ORidBag))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as LINKBAG but an incompatible type is used. Value: "
                  + fieldValue);
        validateLinkCollection(p, (Iterable<Object>) fieldValue, entry);
        break;
      case EMBEDDED:
        validateEmbedded(p, fieldValue);
        break;
      case EMBEDDEDLIST:
        if (!(fieldValue instanceof List))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as EMBEDDEDLIST but an incompatible type is used. Value: "
                  + fieldValue);
        if (p.getLinkedClass() != null) {
          for (Object item : ((List<?>) fieldValue))
            validateEmbedded(p, item);
        } else if (p.getLinkedType() != null) {
          for (Object item : ((List<?>) fieldValue))
            validateType(p, item);
        }
        break;
      case EMBEDDEDSET:
        if (!(fieldValue instanceof Set))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as EMBEDDEDSET but an incompatible type is used. Value: "
                  + fieldValue);
        if (p.getLinkedClass() != null) {
          for (Object item : ((Set<?>) fieldValue))
            validateEmbedded(p, item);
        } else if (p.getLinkedType() != null) {
          for (Object item : ((Set<?>) fieldValue))
            validateType(p, item);
        }
        break;
      case EMBEDDEDMAP:
        if (!(fieldValue instanceof Map))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as EMBEDDEDMAP but an incompatible type is used. Value: "
                  + fieldValue);
        if (p.getLinkedClass() != null) {
          for (Entry<?, ?> colleEntry : ((Map<?, ?>) fieldValue).entrySet())
            validateEmbedded(p, colleEntry.getValue());
        } else if (p.getLinkedType() != null) {
          for (Entry<?, ?> collEntry : ((Map<?, ?>) fieldValue).entrySet())
            validateType(p, collEntry.getValue());
        }
        break;
      }
    }

    if (p.getMin() != null && fieldValue != null) {
      // MIN
      final String min = p.getMin();
      if (p.getMinComparable().compareTo(fieldValue) > 0) {
        switch (p.getType()) {
        case STRING:
          throw new OValidationException(
              "The field '" + p.getFullName() + "' contains fewer characters than " + min + " requested");
        case DATE:
        case DATETIME:
          throw new OValidationException(
              "The field '" + p.getFullName() + "' contains the date " + fieldValue + " which precedes the first acceptable date ("
                  + min + ")");
        case BINARY:
          throw new OValidationException("The field '" + p.getFullName() + "' contains fewer bytes than " + min + " requested");
        case EMBEDDEDLIST:
        case EMBEDDEDSET:
        case LINKLIST:
        case LINKSET:
        case EMBEDDEDMAP:
        case LINKMAP:
          throw new OValidationException("The field '" + p.getFullName() + "' contains fewer items than " + min + " requested");
        default:
          throw new OValidationException("The field '" + p.getFullName() + "' is less than " + min);
        }
      }
    }

    if (p.getMaxComparable() != null && fieldValue != null) {
      final String max = p.getMax();
      if (p.getMaxComparable().compareTo(fieldValue) < 0) {
        switch (p.getType()) {
        case STRING:
          throw new OValidationException("The field '" + p.getFullName() + "' contains more characters than " + max + " requested");
        case DATE:
        case DATETIME:
          throw new OValidationException(
              "The field '" + p.getFullName() + "' contains the date " + fieldValue + " which is after the last acceptable date ("
                  + max + ")");
        case BINARY:
          throw new OValidationException("The field '" + p.getFullName() + "' contains more bytes than " + max + " requested");
        case EMBEDDEDLIST:
        case EMBEDDEDSET:
        case LINKLIST:
        case LINKSET:
        case EMBEDDEDMAP:
        case LINKMAP:
          throw new OValidationException("The field '" + p.getFullName() + "' contains more items than " + max + " requested");
        default:
          throw new OValidationException("The field '" + p.getFullName() + "' is greater than " + max);
        }
      }
    }

    if (p.isReadonly() && !ORecordVersionHelper.isTombstone(iRecord.getVersion())) {
      if (entry != null && (entry.changed || entry.timeLine != null) && !entry.created) {
        // check if the field is actually changed by equal.
        // this is due to a limitation in the merge algorithm used server side marking all non simple fields as dirty
        Object orgVal = entry.original;
        boolean simple = fieldValue != null ? OType.isSimpleType(fieldValue) : OType.isSimpleType(orgVal);
        if ((simple) || (fieldValue != null && orgVal == null) || (fieldValue == null && orgVal != null) || (fieldValue != null
            && !fieldValue.equals(orgVal)))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' is immutable and cannot be altered. Field value is: " + entry.value);
      }
    }
  }

  protected static void validateLinkCollection(final OProperty property, Iterable<Object> values, ODocumentEntry value) {
    if (property.getLinkedClass() != null) {
      if (value.timeLine != null) {
        List<OMultiValueChangeEvent<Object, Object>> event = value.timeLine.getMultiValueChangeEvents();
        for (OMultiValueChangeEvent object : event) {
          if (object.getChangeType() == OMultiValueChangeEvent.OChangeType.ADD
              || object.getChangeType() == OMultiValueChangeEvent.OChangeType.UPDATE && object.getValue() != null)
            validateLink(property, object.getValue(), OSecurityShared.ALLOW_FIELDS.contains(property.getName()));
        }
      } else {
        boolean autoconvert = false;
        if (values instanceof ORecordLazyMultiValue) {
          autoconvert = ((ORecordLazyMultiValue) values).isAutoConvertToRecord();
          ((ORecordLazyMultiValue) values).setAutoConvertToRecord(false);
        }
        for (Object object : values) {
          validateLink(property, object, OSecurityShared.ALLOW_FIELDS.contains(property.getName()));
        }
        if (values instanceof ORecordLazyMultiValue)
          ((ORecordLazyMultiValue) values).setAutoConvertToRecord(autoconvert);
      }
    }
  }

  protected static void validateType(final OProperty p, final Object value) {
    if (value != null)
      if (OType.convert(value, p.getLinkedType().getDefaultJavaType()) == null)
        throw new OValidationException(
            "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " of type '" + p.getLinkedType()
                + "' but the value is " + value);
  }

  protected static void validateLink(final OProperty p, final Object fieldValue, boolean allowNull) {
    if (fieldValue == null) {
      if (allowNull)
        return;
      else
        throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
            + " but contains a null record (probably a deleted record?)");
    }

    final ORecord linkedRecord;
    if (!(fieldValue instanceof OIdentifiable))
      throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
          + " but the value is not a record or a record-id");
    final OClass schemaClass = p.getLinkedClass();
    if (schemaClass != null && !schemaClass.isSubClassOf(OIdentity.CLASS_NAME)) {
      // DON'T VALIDATE OUSER AND OROLE FOR SECURITY RESTRICTIONS

      final ORID rid = ((OIdentifiable) fieldValue).getIdentity();

      if (!schemaClass.hasPolymorphicClusterId(rid.getClusterId())) {
        linkedRecord = ((OIdentifiable) fieldValue).getRecord();
        if (linkedRecord != null) {
          if (!(linkedRecord instanceof ODocument))
            throw new OValidationException(
                "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " of type '" + schemaClass
                    + "' but the value is the record " + linkedRecord.getIdentity() + " that is not a document");

          final ODocument doc = (ODocument) linkedRecord;

          // AT THIS POINT CHECK THE CLASS ONLY IF != NULL BECAUSE IN CASE OF GRAPHS THE RECORD COULD BE PARTIAL
          if (doc.getImmutableSchemaClass() != null && !schemaClass.isSuperClassOf(doc.getImmutableSchemaClass()))
            throw new OValidationException(
                "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " of type '" + schemaClass.getName()
                    + "' but the value is the document " + linkedRecord.getIdentity() + " of class '" + doc
                    .getImmutableSchemaClass() + "'");
        }
      }
    }
  }

  protected static void validateEmbedded(final OProperty p, final Object fieldValue) {
    if (fieldValue == null)
      return;
    if (fieldValue instanceof ORecordId)
      throw new OValidationException(
          "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " but the value is the RecordID "
              + fieldValue);
    else if (fieldValue instanceof OIdentifiable) {
      final OIdentifiable embedded = (OIdentifiable) fieldValue;
      if (embedded.getIdentity().isValid())
        throw new OValidationException("The field '" + p.getFullName() + "' has been declared as " + p.getType()
            + " but the value is a document with the valid RecordID " + fieldValue);

      final OClass embeddedClass = p.getLinkedClass();
      if (embeddedClass != null) {
        final ORecord embeddedRecord = embedded.getRecord();
        if (!(embeddedRecord instanceof ODocument))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " with linked class '" + embeddedClass
                  + "' but the record was not a document");

        final ODocument doc = (ODocument) embeddedRecord;
        if (doc.getImmutableSchemaClass() == null)
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " with linked class '" + embeddedClass
                  + "' but the record has no class");

        if (!(doc.getImmutableSchemaClass().isSubClassOf(embeddedClass)))
          throw new OValidationException(
              "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " with linked class '" + embeddedClass
                  + "' but the record is of class '" + doc.getImmutableSchemaClass().getName()
                  + "' that is not a subclass of that");

        doc.validate();
      }

    } else
      throw new OValidationException(
          "The field '" + p.getFullName() + "' has been declared as " + p.getType() + " but an incompatible type is used. Value: "
              + fieldValue);
  }

  /**
   * Copies the current instance to a new one. Hasn't been choose the clone() to let ODocument return type. Once copied the new
   * instance has the same identity and values but all the internal structure are totally independent by the source.
   */
  public ODocument copy() {
    return (ODocument) copyTo(new ODocument());
  }

  /**
   * Copies all the fields into iDestination document.
   */
  @Override
  public ORecordAbstract copyTo(final ORecordAbstract iDestination) {
    // TODO: REMOVE THIS
    checkForFields();

    ODocument destination = (ODocument) iDestination;

    super.copyTo(iDestination);

    destination._ordered = _ordered;

    destination._className = _className;
    destination._immutableSchemaVersion = -1;
    destination._immutableClazz = null;

    destination._trackingChanges = _trackingChanges;
    if (_owners != null)
      destination._owners = new ArrayList<>(_owners);
    else
      destination._owners = null;

    if (_fields != null) {
      destination._fields = _fields instanceof LinkedHashMap ? new LinkedHashMap<>() : new HashMap<>();
      for (Entry<String, ODocumentEntry> entry : _fields.entrySet()) {
        ODocumentEntry docEntry = entry.getValue().clone();
        destination._fields.put(entry.getKey(), docEntry);
        docEntry.value = ODocumentHelper.cloneValue(destination, entry.getValue().value);
      }
    } else
      destination._fields = null;
    destination._fieldSize = _fieldSize;
    destination.addAllMultiValueChangeListeners();

    destination._dirty = _dirty; // LEAVE IT AS LAST TO AVOID SOMETHING SET THE FLAG TO TRUE
    destination._contentChanged = _contentChanged;

    return destination;
  }

  /**
   * Returns an empty record as place-holder of the current. Used when a record is requested, but only the identity is needed.
   *
   * @return placeholder of this document
   */
  public ORecord placeholder() {
    final ODocument cloned = new ODocument();
    cloned._source = null;
    cloned._recordId = _recordId;
    cloned._status = STATUS.NOT_LOADED;
    cloned._dirty = false;
    cloned._contentChanged = false;
    return cloned;
  }

  /**
   * Detaches all the connected records. If new records are linked to the document the detaching cannot be completed and false will
   * be returned. RidBag types cannot be fully detached when the database is connected using "remote" protocol.
   *
   * @return true if the record has been detached, otherwise false
   */
  public boolean detach() {
    deserializeFields();
    boolean fullyDetached = true;

    if (_fields != null) {
      Object fieldValue;
      for (Map.Entry<String, ODocumentEntry> entry : _fields.entrySet()) {
        fieldValue = entry.getValue().value;

        if (fieldValue instanceof ORecord)
          if (((ORecord) fieldValue).getIdentity().isNew())
            fullyDetached = false;
          else
            entry.getValue().value = ((ORecord) fieldValue).getIdentity();

        if (fieldValue instanceof ODetachable) {
          if (!((ODetachable) fieldValue).detach())
            fullyDetached = false;
        }
      }
    }

    return fullyDetached;
  }

  /**
   * Loads the record using a fetch plan. Example:
   * <p>
   * <code>doc.load( "*:3" ); // LOAD THE DOCUMENT BY EARLY FETCHING UP TO 3rd LEVEL OF CONNECTIONS</code>
   * </p>
   *
   * @param iFetchPlan Fetch plan to use
   */
  public ODocument load(final String iFetchPlan) {
    return load(iFetchPlan, false);
  }

  /**
   * Loads the record using a fetch plan. Example:
   * <p>
   * <code>doc.load( "*:3", true ); // LOAD THE DOCUMENT BY EARLY FETCHING UP TO 3rd LEVEL OF CONNECTIONS IGNORING THE CACHE</code>
   * </p>
   *
   * @param iIgnoreCache Ignore the cache or use it
   */
  public ODocument load(final String iFetchPlan, boolean iIgnoreCache) {
    Object result;
    try {
      result = getDatabase().load(this, iFetchPlan, iIgnoreCache);
    } catch (Exception e) {
      throw OException.wrapException(new ORecordNotFoundException(getIdentity()), e);
    }

    if (result == null)
      throw new ORecordNotFoundException(getIdentity());

    return (ODocument) result;
  }

  @Deprecated
  public ODocument load(final String iFetchPlan, boolean iIgnoreCache, boolean loadTombstone) {
    Object result;
    try {
      result = getDatabase().load(this, iFetchPlan, iIgnoreCache, loadTombstone, OStorage.LOCKING_STRATEGY.DEFAULT);
    } catch (Exception e) {
      throw OException.wrapException(new ORecordNotFoundException(getIdentity()), e);
    }

    if (result == null)
      throw new ORecordNotFoundException(getIdentity());

    return (ODocument) result;
  }

  @Override
  public ODocument reload(final String fetchPlan, final boolean ignoreCache) {
    super.reload(fetchPlan, ignoreCache);
    if (!_lazyLoad) {
      checkForLoading();
      checkForFields();
    }
    return this;
  }

  public boolean hasSameContentOf(final ODocument iOther) {
    final ODatabaseDocumentInternal currentDb = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    return ODocumentHelper.hasSameContentOf(this, currentDb, iOther, currentDb, null);
  }

  @Override
  public byte[] toStream() {
    if (_recordFormat == null)
      setup();
    return toStream(false);
  }

  /**
   * Returns the document as Map String,Object . If the document has identity, then the @rid entry is valued. If the document has a
   * class, then the @class entry is valued.
   *
   * @since 2.0
   */
  public Map<String, Object> toMap() {
    final Map<String, Object> map = new HashMap<>();
    for (String field : fieldNames())
      map.put(field, field(field));

    final ORID id = getIdentity();
    if (id.isValid())
      map.put(ODocumentHelper.ATTRIBUTE_RID, id);

    final String className = getClassName();
    if (className != null)
      map.put(ODocumentHelper.ATTRIBUTE_CLASS, className);

    return map;
  }

  /**
   * Dumps the instance as string.
   */
  @Override
  public String toString() {
    return toString(new HashSet<>());
  }

  /**
   * Fills the ODocument directly with the string representation of the document itself. Use it for faster insertion but pay
   * attention to respect the OrientDB record format. <p> <code> record.reset();<br> record.setClassName("Account");<br>
   * record.fromString(new String("Account@id:" + data.getCyclesDone() + ",name:'Luca',surname:'Garulli',birthDate:" +
   * date.getTime()<br> + ",salary:" + 3000f + i));<br> record.save();<br> </code> </p>
   *
   * @param iValue String representation of the record.
   */
  @Deprecated
  public void fromString(final String iValue) {
    _dirty = true;
    _contentChanged = true;
    try {
      _source = iValue.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw OException.wrapException(new OSerializationException("Error reading content from string"), e);
    }

    removeAllCollectionChangeListeners();

    _fields = null;
    _fieldSize = 0;
  }

  /**
   * Returns the set of field names.
   */
  public String[] fieldNames() {
    checkForLoading();

    if (_status == ORecordElement.STATUS.LOADED && _source != null && ODatabaseRecordThreadLocal.INSTANCE.isDefined()
        && !ODatabaseRecordThreadLocal.INSTANCE.get().isClosed()) {
      // DESERIALIZE FIELD NAMES ONLY (SUPPORTED ONLY BY BINARY SERIALIZER)
      final String[] fieldNames = _recordFormat.getFieldNames(this, _source);
      if (fieldNames != null)
        return fieldNames;
    }

    checkForFields();

    if (_fields == null || _fields.size() == 0)
      return EMPTY_STRINGS;
    final List<String> names = new ArrayList<>(_fields.size());
    for (Entry<String, ODocumentEntry> entry : _fields.entrySet()) {
      if (entry.getValue().exist())
        names.add(entry.getKey());
    }
    return names.toArray(new String[names.size()]);
  }

  private Set<String> arrayToSet(String[] fieldNames) {
    Set<String> result = new HashSet<>();
    Collections.addAll(result, fieldNames);
    return result;
  }

  /**
   * Returns the array of field values.
   */
  public Object[] fieldValues() {
    checkForLoading();
    checkForFields();
    Object[] res = new Object[_fields.size()];
    int i = 0;
    for (ODocumentEntry entry : _fields.values()) {
      res[i++] = entry.value;
    }
    return res;
  }

  public <RET> RET rawField(final String iFieldName) {
    if (iFieldName == null || iFieldName.length() == 0)
      return null;

    checkForLoading();
    if (!checkForFields(iFieldName))
      // NO FIELDS
      return null;

    // OPTIMIZATION
    if (!_allowChainedAccess || (iFieldName.charAt(0) != '@' && OStringSerializerHelper.indexOf(iFieldName, 0, '.', '[') == -1)) {
      ODocumentEntry entry = _fields.get(iFieldName);
      if (entry != null && entry.exist())
        return (RET) entry.value;
      else
        return null;
    }

    // NOT FOUND, PARSE THE FIELD NAME
    return (RET) ODocumentHelper.getFieldValue(this, iFieldName);
  }

  /**
   * Evaluates a SQL expression against current document. Example: <code>long amountPlusVat = doc.eval("amount * 120 / 100");</code>
   *
   * @param iExpression SQL expression to evaluate.
   *
   * @return The result of expression
   *
   * @throws OQueryParsingException in case the expression is not valid
   */
  public Object eval(final String iExpression) {
    return eval(iExpression, null);
  }

  /**
   * Evaluates a SQL expression against current document by passing a context. The expression can refer to the variables contained
   * in the context. Example: <code>
   * OCommandContext context = new OBasicCommandContext().setVariable("vat", 20);
   * long amountPlusVat = doc.eval("amount * (100+$vat) / 100", context);
   * </code>
   *
   * @param iExpression SQL expression to evaluate.
   *
   * @return The result of expression
   *
   * @throws OQueryParsingException in case the expression is not valid
   */
  public Object eval(final String iExpression, final OCommandContext iContext) {
    return new OSQLPredicate(iExpression).evaluate(this, null, iContext);
  }

  /**
   * Reads the field value.
   *
   * @param iFieldName field name
   *
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName) {
    RET value = this.rawField(iFieldName);

    if (!iFieldName.startsWith("@") && _lazyLoad && value instanceof ORID && (((ORID) value).isPersistent() || ((ORID) value)
        .isNew()) && ODatabaseRecordThreadLocal.INSTANCE.isDefined()) {
      // CREATE THE DOCUMENT OBJECT IN LAZY WAY
      RET newValue = getDatabase().load((ORID) value);
      if (newValue != null) {
        unTrack((ORID) value);
        track((OIdentifiable) newValue);
        value = newValue;
        if (this.isTrackingChanges()) {
          ORecordInternal.setDirtyManager((ORecord) value, this.getDirtyManager());
        }
        if (!iFieldName.contains(".")) {
          ODocumentEntry entry = _fields.get(iFieldName);
          removeCollectionChangeListener(entry, entry.value);
          entry.value = value;
          addCollectionChangeListener(entry);
        }
      }
    }

    return value;
  }

  /**
   * Reads the field value forcing the return type. Use this method to force return of ORID instead of the entire document by
   * passing ORID.class as iFieldType.
   *
   * @param iFieldName field name
   * @param iFieldType Forced type.
   *
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName, final Class<?> iFieldType) {
    RET value = this.rawField(iFieldName);

    if (value != null)
      value = ODocumentHelper.convertField(this, iFieldName, OType.getTypeByClass(iFieldType), iFieldType, value);

    return value;
  }

  /**
   * Reads the field value forcing the return type. Use this method to force return of binary data.
   *
   * @param iFieldName field name
   * @param iFieldType Forced type.
   *
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName, final OType iFieldType) {
    RET value = field(iFieldName);
    OType original;
    if (iFieldType != null && iFieldType != (original = fieldType(iFieldName))) {
      // this is needed for the csv serializer that don't give back values
      if (original == null) {
        original = OType.getTypeByValue(value);
        if (iFieldType == original)
          return value;
      }

      final Object newValue;

      if (iFieldType == OType.BINARY && value instanceof String)
        newValue = OStringSerializerHelper.getBinaryContent(value);
      else if (iFieldType == OType.DATE && value instanceof Long)
        newValue = new Date((Long) value);
      else if ((iFieldType == OType.EMBEDDEDSET || iFieldType == OType.LINKSET) && value instanceof List)
        newValue = Collections.unmodifiableSet((Set<?>) ODocumentHelper.convertField(this, iFieldName, iFieldType, null, value));
      else if ((iFieldType == OType.EMBEDDEDLIST || iFieldType == OType.LINKLIST) && value instanceof Set)
        newValue = Collections.unmodifiableList((List<?>) ODocumentHelper.convertField(this, iFieldName, iFieldType, null, value));
      else if ((iFieldType == OType.EMBEDDEDMAP || iFieldType == OType.LINKMAP) && value instanceof Map)
        newValue = Collections.unmodifiableMap((Map<?, ?>) ODocumentHelper.convertField(this, iFieldName, iFieldType, null, value));
      else
        newValue = OType.convert(value, iFieldType.getDefaultJavaType());

      if (newValue != null)
        value = (RET) newValue;

    }
    return value;
  }

  /**
   * Writes the field value. This method sets the current document as dirty.
   *
   * @param iFieldName     field name. If contains dots (.) the change is applied to the nested documents in chain. To disable this
   *                       feature call {@link #setAllowChainedAccess(boolean)} to false.
   * @param iPropertyValue field value
   *
   * @return The Record instance itself giving a "fluent interface". Useful to call multiple methods in chain.
   */
  public ODocument field(final String iFieldName, Object iPropertyValue) {
    return field(iFieldName, iPropertyValue, OCommonConst.EMPTY_TYPES_ARRAY);
  }

  /**
   * Fills a document passing the field names/values.
   */
  public ODocument fields(final String iFieldName, final Object iFieldValue, final Object... iFields) {
    if (iFields != null && iFields.length % 2 != 0)
      throw new IllegalArgumentException("Fields must be passed in pairs as name and value");

    field(iFieldName, iFieldValue);
    if (iFields != null && iFields.length > 0)
      for (int i = 0; i < iFields.length; i += 2) {
        field(iFields[i].toString(), iFields[i + 1]);
      }
    return this;
  }

  /**
   * Deprecated. Use fromMap(Map) instead.<br>
   * Fills a document passing the field names/values as a Map String,Object where the keys are the field names and the values are
   * the field values.
   *
   * @see #fromMap(Map)
   */
  @Deprecated
  public ODocument fields(final Map<String, Object> iMap) {
    return fromMap(iMap);
  }

  /**
   * Fills a document passing the field names/values as a Map String,Object where the keys are the field names and the values are
   * the field values. It accepts also @rid for record id and @class for class name.
   *
   * @since 2.0
   */
  public ODocument fromMap(final Map<String, ?> iMap) {
    if (iMap != null) {
      for (Entry<String, ?> entry : iMap.entrySet())
        field(entry.getKey(), entry.getValue());
    }
    return this;
  }

  /**
   * Writes the field value forcing the type. This method sets the current document as dirty.
   * <p>
   * if there's a schema definition for the specified field, the value will be converted to respect the schema definition if needed.
   * if the type defined in the schema support less precision than the iPropertyValue provided, the iPropertyValue will be converted
   * following the java casting rules with possible precision loss.
   *
   * @param iFieldName     field name. If contains dots (.) the change is applied to the nested documents in chain. To disable this
   *                       feature call {@link #setAllowChainedAccess(boolean)} to false.
   * @param iPropertyValue field value.
   * @param iFieldType     Forced type (not auto-determined)
   *
   * @return The Record instance itself giving a "fluent interface". Useful to call multiple methods in chain. If the updated
   * document is another document (using the dot (.) notation) then the document returned is the changed one or NULL if no document
   * has been found in chain
   */
  public ODocument field(String iFieldName, Object iPropertyValue, OType... iFieldType) {
    if (iFieldName == null)
      throw new IllegalArgumentException("Field is null");

    if (iFieldName.isEmpty())
      throw new IllegalArgumentException("Field name is empty");

    if (ODocumentHelper.ATTRIBUTE_CLASS.equals(iFieldName)) {
      setClassName(iPropertyValue.toString());
      return this;
    } else if (ODocumentHelper.ATTRIBUTE_RID.equals(iFieldName)) {
      _recordId.fromString(iPropertyValue.toString());
      return this;
    } else if (ODocumentHelper.ATTRIBUTE_VERSION.equals(iFieldName)) {
      if (iPropertyValue != null) {
        int v;

        if (iPropertyValue instanceof Number)
          v = ((Number) iPropertyValue).intValue();
        else
          v = Integer.parseInt(iPropertyValue.toString());

        _recordVersion = v;
      }
      return this;
    }

    final int lastDotSep = _allowChainedAccess ? iFieldName.lastIndexOf('.') : -1;
    final int lastArraySep = _allowChainedAccess ? iFieldName.lastIndexOf('[') : -1;

    final int lastSep = Math.max(lastArraySep, lastDotSep);
    final boolean lastIsArray = lastArraySep > lastDotSep;

    if (lastSep > -1) {
      // SUB PROPERTY GET 1 LEVEL BEFORE LAST
      final Object subObject = field(iFieldName.substring(0, lastSep));
      if (subObject != null) {
        final String subFieldName = lastIsArray ? iFieldName.substring(lastSep) : iFieldName.substring(lastSep + 1);
        if (subObject instanceof ODocument) {
          // SUB-DOCUMENT
          ((ODocument) subObject).field(subFieldName, iPropertyValue);
          return (ODocument) (((ODocument) subObject).isEmbedded() ? this : subObject);
        } else if (subObject instanceof Map<?, ?>) {
          // KEY/VALUE
          ((Map<String, Object>) subObject).put(subFieldName, iPropertyValue);
        } else if (OMultiValue.isMultiValue(subObject)) {
          if ((subObject instanceof List<?> || subObject.getClass().isArray()) && lastIsArray) {
            // List // Array Type with a index subscript.
            final int subFieldNameLen = subFieldName.length();

            if (subFieldName.charAt(subFieldNameLen - 1) != ']') {
              throw new IllegalArgumentException("Missed closing ']'");
            }

            final String indexPart = subFieldName.substring(1, subFieldNameLen - 1);
            final Object indexPartObject = ODocumentHelper.getIndexPart(null, indexPart);
            final String indexAsString = indexPartObject == null ? null : indexPartObject.toString();

            try {
              final int index = Integer.parseInt(indexAsString);
              OMultiValue.setValue(subObject, iPropertyValue, index);
            } catch (NumberFormatException e) {
              throw new IllegalArgumentException("List / array subscripts must resolve to integer values.", e);
            }
          } else {
            // APPLY CHANGE TO ALL THE ITEM IN SUB-COLLECTION
            for (Object subObjectItem : OMultiValue.getMultiValueIterable(subObject)) {
              if (subObjectItem instanceof ODocument) {
                // SUB-DOCUMENT, CHECK IF IT'S NOT LINKED
                if (!((ODocument) subObjectItem).isEmbedded())
                  throw new IllegalArgumentException("Property '" + iFieldName
                      + "' points to linked collection of items. You can only change embedded documents in this way");
                ((ODocument) subObjectItem).field(subFieldName, iPropertyValue);
              } else if (subObjectItem instanceof Map<?, ?>) {
                // KEY/VALUE
                ((Map<String, Object>) subObjectItem).put(subFieldName, iPropertyValue);
              }
            }
          }
          return this;
        }
      } else
        throw new IllegalArgumentException("Property '" + iFieldName.substring(0, lastSep)
            + "' is null, is possible to set a value with dotted notation only on not null property");
      return null;
    }

    iFieldName = checkFieldName(iFieldName);

    checkForLoading();
    checkForFields();

    ODocumentEntry entry = _fields.get(iFieldName);
    final boolean knownProperty;
    final Object oldValue;
    final OType oldType;
    if (entry == null) {
      entry = new ODocumentEntry();
      _fieldSize++;
      _fields.put(iFieldName, entry);
      entry.setCreated(true);
      knownProperty = false;
      oldValue = null;
      oldType = null;
    } else {
      knownProperty = entry.exist();
      oldValue = entry.value;
      oldType = entry.type;
    }
    OType fieldType = deriveFieldType(iFieldName, entry, iFieldType);
    if (iPropertyValue != null && fieldType != null) {
      iPropertyValue = ODocumentHelper.convertField(this, iFieldName, fieldType, null, iPropertyValue);
    } else if (iPropertyValue instanceof Enum)
      iPropertyValue = iPropertyValue.toString();

    if (knownProperty)
      // CHECK IF IS REALLY CHANGED
      if (iPropertyValue == null) {
        if (oldValue == null)
          // BOTH NULL: UNCHANGED
          return this;
      } else {

        try {
          if (iPropertyValue.equals(oldValue)) {
            if (fieldType == oldType) {
              if (!(iPropertyValue instanceof ORecordElement))
                // SAME BUT NOT TRACKABLE: SET THE RECORD AS DIRTY TO BE SURE IT'S SAVED
                setDirty();

              // SAVE VALUE: UNCHANGED
              return this;
            }
          } else if (iPropertyValue instanceof byte[] && Arrays.equals((byte[]) iPropertyValue, (byte[]) oldValue)) {
            // SAVE VALUE: UNCHANGED
            return this;
          }
        } catch (Exception e) {
          OLogManager.instance()
              .warn(this, "Error on checking the value of property %s against the record %s", e, iFieldName, getIdentity());
        }
      }

    if (oldValue instanceof ORidBag) {
      final ORidBag ridBag = (ORidBag) oldValue;
      ridBag.setOwner(null);
    } else if (oldValue instanceof ODocument) {
      ((ODocument) oldValue).removeOwner(this);
    }

    if (oldValue instanceof OIdentifiable) {
      unTrack((OIdentifiable) oldValue);
    }

    if (iPropertyValue != null) {
      if (iPropertyValue instanceof ODocument) {
        if (OType.EMBEDDED.equals(fieldType)) {
          final ODocument embeddedDocument = (ODocument) iPropertyValue;
          ODocumentInternal.addOwner(embeddedDocument, this);
        } else if (OType.LINK.equals(fieldType)) {
          final ODocument embeddedDocument = (ODocument) iPropertyValue;
          ODocumentInternal.removeOwner(embeddedDocument, this);
        }
      }
      if (iPropertyValue instanceof OIdentifiable) {
        track((OIdentifiable) iPropertyValue);
      }

      if (iPropertyValue instanceof ORidBag) {
        final ORidBag ridBag = (ORidBag) iPropertyValue;
        ridBag.setOwner(null); // in order to avoid IllegalStateException when ridBag changes the owner (ODocument.merge)
        ridBag.setOwner(this);
      }
    }

    if (oldType != fieldType && oldType != null) {
      // can be made in a better way, but "keeping type" issue should be solved before
      if (iPropertyValue == null || fieldType != null || oldType != OType.getTypeByValue(iPropertyValue))
        entry.type = fieldType;
    }
    removeCollectionChangeListener(entry, oldValue);
    entry.value = iPropertyValue;
    if (!entry.exist()) {
      entry.setExist(true);
      _fieldSize++;
    }
    addCollectionChangeListener(entry);

    if (_status != STATUS.UNMARSHALLING) {
      setDirty();
      if (!entry.isChanged()) {
        entry.original = oldValue;
        entry.setChanged(true);
      }
    }

    return this;
  }

  /**
   * Removes a field.
   */
  public Object removeField(final String iFieldName) {
    checkForLoading();
    checkForFields();

    if (ODocumentHelper.ATTRIBUTE_CLASS.equalsIgnoreCase(iFieldName)) {
      setClassName(null);
    } else if (ODocumentHelper.ATTRIBUTE_RID.equalsIgnoreCase(iFieldName)) {
      _recordId = new ORecordId();
    }

    final ODocumentEntry entry = _fields.get(iFieldName);
    if (entry == null)
      return null;
    Object oldValue = entry.value;
    if (entry.exist() && _trackingChanges) {
      // SAVE THE OLD VALUE IN A SEPARATE MAP
      if (entry.original == null)
        entry.original = entry.value;
      entry.value = null;
      entry.setExist(false);
      entry.setChanged(true);
    } else {
      _fields.remove(iFieldName);
    }
    _fieldSize--;

    removeCollectionChangeListener(entry, oldValue);
    if (oldValue instanceof OIdentifiable)
      unTrack((OIdentifiable) oldValue);
    if (oldValue instanceof ORidBag)
      ((ORidBag) oldValue).setOwner(null);
    setDirty();
    return oldValue;
  }

  /**
   * Merge current document with the document passed as parameter. If the field already exists then the conflicts are managed based
   * on the value of the parameter 'iUpdateOnlyMode'.
   *
   * @param iOther                              Other ODocument instance to merge
   * @param iUpdateOnlyMode                     if true, the other document properties will always be added or overwritten. If
   *                                            false, the missed properties in the "other" document will be removed by original
   *                                            document
   * @param iMergeSingleItemsOfMultiValueFields If true, merges single items of multi field fields (collections, maps, arrays, etc)
   *
   * @return
   */
  public ODocument merge(final ODocument iOther, boolean iUpdateOnlyMode, boolean iMergeSingleItemsOfMultiValueFields) {
    iOther.checkForLoading();
    iOther.checkForFields();

    if (_className == null && iOther.getImmutableSchemaClass() != null)
      _className = iOther.getImmutableSchemaClass().getName();

    return mergeMap(iOther._fields, iUpdateOnlyMode, iMergeSingleItemsOfMultiValueFields);
  }

  /**
   * Merge current document with the document passed as parameter. If the field already exists then the conflicts are managed based
   * on the value of the parameter 'iUpdateOnlyMode'.
   *
   * @param iOther                              Other ODocument instance to merge
   * @param iUpdateOnlyMode                     if true, the other document properties will always be added or overwritten. If
   *                                            false, the missed properties in the "other" document will be removed by original
   *                                            document
   * @param iMergeSingleItemsOfMultiValueFields If true, merges single items of multi field fields (collections, maps, arrays, etc)
   *
   * @return
   */
  public ODocument merge(final Map<String, Object> iOther, final boolean iUpdateOnlyMode,
      boolean iMergeSingleItemsOfMultiValueFields) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns list of changed fields. There are two types of changes:
   * <ol>
   * <li>Value of field itself was changed by calling of {@link #field(String, Object)} method for example.</li>
   * <li>Internal state of field was changed but was not saved. This case currently is applicable for for collections only.</li>
   * </ol>
   *
   * @return List of fields, values of which were changed.
   */
  public String[] getDirtyFields() {
    if (_fields == null || _fields.isEmpty())
      return EMPTY_STRINGS;

    final Set<String> dirtyFields = new HashSet<>();
    for (Entry<String, ODocumentEntry> entry : _fields.entrySet()) {
      if (entry.getValue().isChanged() || entry.getValue().timeLine != null)
        dirtyFields.add(entry.getKey());
    }
    return dirtyFields.toArray(new String[dirtyFields.size()]);
  }

  /**
   * Returns the original value of a field before it has been changed.
   *
   * @param iFieldName Property name to retrieve the original value
   */
  public Object getOriginalValue(final String iFieldName) {
    if (_fields != null) {
      ODocumentEntry entry = _fields.get(iFieldName);
      if (entry != null)
        return entry.original;
    }
    return null;
  }

  public OMultiValueChangeTimeLine<Object, Object> getCollectionTimeLine(final String iFieldName) {
    ODocumentEntry entry = _fields != null ? _fields.get(iFieldName) : null;
    return entry != null ? entry.timeLine : null;
  }

  /**
   * Returns the iterator fields
   */
  public Iterator<Entry<String, Object>> iterator() {
    checkForLoading();
    checkForFields();

    if (_fields == null)
      return OEmptyMapEntryIterator.INSTANCE;

    final Iterator<Entry<String, ODocumentEntry>> iterator = _fields.entrySet().iterator();
    return new Iterator<Entry<String, Object>>() {
      private Entry<String, ODocumentEntry> current;
      private boolean read = true;

      public boolean hasNext() {
        while (iterator.hasNext()) {
          current = iterator.next();
          if (current.getValue().exist()) {
            read = false;
            return true;
          }
        }
        return false;
      }

      public Entry<String, Object> next() {
        if (read)
          if (!hasNext()) {
            // Look wrong but is correct, it need to fail if there isn't next.
            iterator.next();
          }
        final Entry<String, Object> toRet = new Entry<String, Object>() {
          private Entry<String, ODocumentEntry> intern = current;

          @Override
          public Object setValue(Object value) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Object getValue() {
            return intern.getValue().value;
          }

          @Override
          public String getKey() {
            return intern.getKey();
          }
        };
        read = true;
        return toRet;
      }

      public void remove() {

        if (_trackingChanges) {
          if (current.getValue().isChanged())
            current.getValue().original = current.getValue().value;
          current.getValue().value = null;
          current.getValue().setExist(false);
          current.getValue().setChanged(true);
        } else
          iterator.remove();
        _fieldSize--;
        removeCollectionChangeListener(current.getValue(), current.getValue().value);
      }
    };
  }

  /**
   * Checks if a field exists.
   *
   * @return True if exists, otherwise false.
   */
  public boolean containsField(final String iFieldName) {
    if (iFieldName == null)
      return false;

    checkForLoading();
    checkForFields(iFieldName);
    ODocumentEntry entry = _fields.get(iFieldName);
    return entry != null && entry.exist();
  }

  /**
   * Returns true if the record has some owner.
   */
  public boolean hasOwners() {
    return _owners != null && !_owners.isEmpty();
  }

  @Override
  public ORecordElement getOwner() {
    if (_owners == null)
      return null;

    for (WeakReference<ORecordElement> _owner : _owners) {
      final ORecordElement e = _owner.get();
      if (e != null)
        return e;
    }

    return null;
  }

  public Iterable<ORecordElement> getOwners() {
    if (_owners == null)
      return Collections.emptyList();

    final List<ORecordElement> result = new ArrayList<>();
    for (WeakReference<ORecordElement> o : _owners) {
      if (o.get() != null)
        result.add(o.get());
    }

    return result;
  }

  /**
   * Propagates the dirty status to the owner, if any. This happens when the object is embedded in another one.
   */
  @Override
  public ORecordAbstract setDirty() {
    if (_owners != null) {
      // PROPAGATES TO THE OWNER
      ORecordElement e;
      for (WeakReference<ORecordElement> o : _owners) {
        e = o.get();
        if (e != null)
          e.setDirty();
      }
    } else if (!isDirty())
      getDirtyManager().setDirty(this);

    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD _SOURCE IS NULL
    checkForFields();

    super.setDirty();

    boolean addToChangedList = false;

    ORecordElement owner;
    if (!isEmbedded())
      owner = this;
    else {
      owner = getOwner();
      while (owner != null && owner.getOwner() != null) {
        owner = owner.getOwner();
      }
    }

    if (owner instanceof ODocument && ((ODocument) owner).isTrackingChanges() && ((ODocument) owner).getIdentity().isPersistent())
      addToChangedList = true;

    if (addToChangedList) {
      final ODatabaseDocumentInternal database = getDatabaseIfDefined();

      if (database != null) {
        final OBasicTransaction transaction = database.getMicroOrRegularTransaction();
        transaction.addChangedDocument(this);
      }
    }

    return this;
  }

  @Override
  public void setDirtyNoChanged() {
    if (_owners != null) {
      // PROPAGATES TO THE OWNER
      ORecordElement e;
      for (WeakReference<ORecordElement> o : _owners) {
        e = o.get();
        if (e != null)
          e.setDirtyNoChanged();
      }
    }

    getDirtyManager().setDirty(this);

    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD _SOURCE IS NULL
    checkForFields();

    super.setDirtyNoChanged();
  }

  @Override
  public ODocument fromStream(final byte[] iRecordBuffer) {
    removeAllCollectionChangeListeners();

    _fields = null;
    _fieldSize = 0;
    _contentChanged = false;
    _schema = null;
    fetchSchemaIfCan();
    super.fromStream(iRecordBuffer);

    if (!_lazyLoad) {
      checkForLoading();
      checkForFields();
    }

    return this;
  }

  /**
   * Returns the forced field type if any.
   *
   * @param iFieldName name of field to check
   */
  public OType fieldType(final String iFieldName) {
    checkForLoading();
    checkForFields(iFieldName);

    ODocumentEntry entry = _fields.get(iFieldName);
    if (entry != null)
      return entry.type;

    return null;
  }

  @Override
  public ODocument unload() {
    super.unload();
    internalReset();
    return this;
  }

  /**
   * <p>
   * Clears all the field values and types. Clears only record content, but saves its identity.
   * </p>
   * <p/>
   * <p>
   * The following code will clear all data from specified document.
   * </p>
   * <code>
   * doc.clear();
   * doc.save();
   * </code>
   *
   * @return this
   *
   * @see #reset()
   */
  @Override
  public ODocument clear() {
    super.clear();
    internalReset();
    _owners = null;
    return this;
  }

  /**
   * <p>
   * Resets the record values and class type to being reused. It's like you create a ODocument from scratch. This method is handy
   * when you want to insert a bunch of documents and don't want to strain GC.
   * </p>
   * <p/>
   * <p>
   * The following code will create a new document in database.
   * </p>
   * <code>
   * doc.clear();
   * doc.save();
   * </code>
   * <p/>
   * <p>
   * IMPORTANT! This can be used only if no transactions are begun.
   * </p>
   *
   * @return this
   *
   * @throws IllegalStateException if transaction is begun.
   * @see #clear()
   */
  @Override
  public ODocument reset() {
    ODatabaseDocument db = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    if (db != null && db.getTransaction().isActive())
      throw new IllegalStateException("Cannot reset documents during a transaction. Create a new one each time");

    super.reset();

    _className = null;
    _immutableClazz = null;
    _immutableSchemaVersion = -1;

    internalReset();

    _owners = null;
    return this;
  }

  /**
   * Rollbacks changes to the loaded version without reloading the document. Works only if tracking changes is enabled @see
   * {@link #isTrackingChanges()} and {@link #setTrackingChanges(boolean)} methods.
   */
  public ODocument undo() {
    if (!_trackingChanges)
      throw new OConfigurationException("Cannot undo the document because tracking of changes is disabled");

    if (_fields != null) {
      Iterator<Entry<String, ODocumentEntry>> vals = _fields.entrySet().iterator();
      while (vals.hasNext()) {
        Entry<String, ODocumentEntry> next = vals.next();
        ODocumentEntry val = next.getValue();
        if (val.created) {
          vals.remove();
        } else if (val.changed) {
          val.value = val.original;
          val.changed = false;
          val.original = null;
          val.exist = true;
        }
      }
      _fieldSize = _fields.size();
    }

    return this;
  }

  public ODocument undo(final String field) {
    if (!_trackingChanges)
      throw new OConfigurationException("Cannot undo the document because tracking of changes is disabled");

    if (_fields != null) {
      final ODocumentEntry value = _fields.get(field);
      if (value != null) {
        if (value.created) {
          _fields.remove(field);
        }
        if (value.changed) {
          value.value = value.original;
          value.original = null;
          value.changed = false;
          value.exist = true;
        }
      }
    }
    return this;
  }

  public boolean isLazyLoad() {
    return _lazyLoad;
  }

  public void setLazyLoad(final boolean iLazyLoad) {
    this._lazyLoad = iLazyLoad;
    checkForFields();

    if (_fields != null) {
      // PROPAGATE LAZINESS TO THE FIELDS
      for (Entry<String, ODocumentEntry> field : _fields.entrySet()) {
        if (field.getValue().value instanceof ORecordLazyMultiValue)
          ((ORecordLazyMultiValue) field.getValue().value).setAutoConvertToRecord(false);
      }
    }
  }

  public boolean isTrackingChanges() {
    return _trackingChanges;
  }

  /**
   * Enabled or disabled the tracking of changes in the document. This is needed by some triggers like
   * {@link com.orientechnologies.orient.core.index.OClassIndexManager} to determine what fields are changed to update indexes.
   *
   * @param iTrackingChanges True to enable it, otherwise false
   *
   * @return this
   */
  public ODocument setTrackingChanges(final boolean iTrackingChanges) {
    this._trackingChanges = iTrackingChanges;
    if (!iTrackingChanges && _fields != null) {
      // FREE RESOURCES
      Iterator<Entry<String, ODocumentEntry>> iter = _fields.entrySet().iterator();
      while (iter.hasNext()) {
        Entry<String, ODocumentEntry> cur = iter.next();
        if (!cur.getValue().exist())
          iter.remove();
        else {
          cur.getValue().setCreated(false);
          cur.getValue().setChanged(false);
          cur.getValue().original = null;
          cur.getValue().timeLine = null;
        }
      }
      removeAllCollectionChangeListeners();
    } else {
      addAllMultiValueChangeListeners();
    }
    return this;
  }

  protected void clearTrackData() {
    if (_fields != null) {
      // FREE RESOURCES
      Iterator<Entry<String, ODocumentEntry>> iter = _fields.entrySet().iterator();
      while (iter.hasNext()) {
        Entry<String, ODocumentEntry> cur = iter.next();
        if (!cur.getValue().exist())
          iter.remove();
        else {
          cur.getValue().setCreated(false);
          cur.getValue().setChanged(false);
          cur.getValue().original = null;
          cur.getValue().timeLine = null;

          if (cur.getValue().changeListener == null && cur.getValue().value instanceof OTrackedMultiValue<?, ?>) {
            addCollectionChangeListener(cur.getValue());
          }
        }
      }
    }
  }

  public boolean isOrdered() {
    return _ordered;
  }

  public ODocument setOrdered(final boolean iOrdered) {
    this._ordered = iOrdered;
    return this;
  }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj))
      return false;

    return this == obj || _recordId.isValid();
  }

  @Override
  public int hashCode() {
    if (_recordId.isValid())
      return super.hashCode();

    return System.identityHashCode(this);
  }

  /**
   * Returns the number of fields in memory.
   */
  public int fields() {
    checkForLoading();
    checkForFields();
    return _fieldSize;
  }

  public boolean isEmpty() {
    checkForLoading();
    checkForFields();
    return _fields == null || _fields.isEmpty();
  }

  @Override
  public ODocument fromJSON(final String iSource, final String iOptions) {
    return (ODocument) super.fromJSON(iSource, iOptions);
  }

  @Override
  public ODocument fromJSON(final String iSource) {
    return (ODocument) super.fromJSON(iSource);
  }

  @Override
  public ODocument fromJSON(final InputStream iContentResult) throws IOException {
    return (ODocument) super.fromJSON(iContentResult);
  }

  @Override
  public ODocument fromJSON(final String iSource, final boolean needReload) {
    return (ODocument) super.fromJSON(iSource, needReload);
  }

  public boolean isEmbedded() {
    return _owners != null && !_owners.isEmpty();
  }

  /**
   * Sets the field type. This overrides the schema property settings if any.
   *
   * @param iFieldName Field name
   * @param iFieldType Type to set between OType enumeration values
   */
  public ODocument setFieldType(final String iFieldName, final OType iFieldType) {
    checkForLoading();
    checkForFields(iFieldName);
    if (iFieldType != null) {
      if (_fields == null)
        _fields = _ordered ? new LinkedHashMap<>() : new HashMap<>();
      // SET THE FORCED TYPE
      ODocumentEntry entry = getOrCreate(iFieldName);
      if (entry.type != iFieldType)
        field(iFieldName, field(iFieldName), iFieldType);
    } else if (_fields != null) {
      // REMOVE THE FIELD TYPE
      ODocumentEntry entry = _fields.get(iFieldName);
      if (entry != null)
        // EMPTY: OPTIMIZE IT BY REMOVING THE ENTIRE MAP
        entry.type = null;
    }
    return this;
  }

  @Override
  public ODocument save() {
    return (ODocument) save(null, false);
  }

  @Override
  public ODocument save(final String iClusterName) {
    return (ODocument) save(iClusterName, false);
  }

  public ORecordAbstract save(final String iClusterName, final boolean forceCreate) {
    return getDatabase().save(this, iClusterName, ODatabase.OPERATION_MODE.SYNCHRONOUS, forceCreate, null, null);
  }

  /*
   * Initializes the object if has been unserialized
   */
  public boolean deserializeFields(final String... iFields) {
    if (_source == null)
      // ALREADY UNMARSHALLED OR JUST EMPTY
      return true;

    if (iFields != null && iFields.length > 0) {
      // EXTRACT REAL FIELD NAMES
      for (int i = 0; i < iFields.length; ++i) {
        final String f = iFields[i];
        if (f != null && !f.startsWith("@")) {
          int pos1 = f.indexOf('[');
          int pos2 = f.indexOf('.');
          if (pos1 > -1 || pos2 > -1) {
            int pos = pos1 > -1 ? pos1 : pos2;
            if (pos2 > -1 && pos2 < pos)
              pos = pos2;

            // REPLACE THE FIELD NAME
            iFields[i] = f.substring(0, pos);
          }
        }
      }

      // CHECK IF HAS BEEN ALREADY UNMARSHALLED
      if (_fields != null && !_fields.isEmpty()) {
        boolean allFound = true;
        for (String f : iFields)
          if (f != null && !f.startsWith("@") && !_fields.containsKey(f)) {
            allFound = false;
            break;
          }

        if (allFound)
          // ALL THE REQUESTED FIELDS HAVE BEEN LOADED BEFORE AND AVAILABLE, AVOID UNMARSHALLIGN
          return true;
      }
    }

    if (_recordFormat == null)
      setup();

    _status = ORecordElement.STATUS.UNMARSHALLING;
    try {
      _recordFormat.fromStream(_source, this, iFields);
    } finally {
      _status = ORecordElement.STATUS.LOADED;
    }

    if (iFields != null && iFields.length > 0) {
      for (String field : iFields) {
        if (field != null && field.startsWith("@"))
          // ATTRIBUTE
          return true;
      }

      // PARTIAL UNMARSHALLING
      if (_fields != null && !_fields.isEmpty())
        for (String f : iFields)
          if (f != null && _fields.containsKey(f))
            return true;

      // NO FIELDS FOUND
      return false;
    } else if (_source != null)
      // FULL UNMARSHALLING
      _source = null;

    return true;
  }

  @Override
  public void writeExternal(ObjectOutput stream) throws IOException {
    ORecordSerializer serializer = ORecordSerializerFactory.instance().getFormat(ORecordSerializerNetwork.NAME);
    final byte[] idBuffer = _recordId.toStream();
    stream.writeInt(-1);
    stream.writeInt(idBuffer.length);
    stream.write(idBuffer);
    stream.writeInt(_recordVersion);

    final byte[] content = serializer.toStream(this, false);
    stream.writeInt(content.length);
    stream.write(content);

    stream.writeBoolean(_dirty);
    stream.writeObject(serializer.toString());
  }

  @Override
  public void readExternal(ObjectInput stream) throws IOException, ClassNotFoundException {
    int i = stream.readInt();
    int size;
    if (i < 0)
      size = stream.readInt();
    else
      size = i;
    final byte[] idBuffer = new byte[size];
    stream.readFully(idBuffer);
    _recordId.fromStream(idBuffer);

    _recordVersion = stream.readInt();

    final int len = stream.readInt();
    final byte[] content = new byte[len];
    stream.readFully(content);

    _dirty = stream.readBoolean();

    ORecordSerializer serializer = _recordFormat;
    if (i < 0) {
      final String str = (String) stream.readObject();
      // TODO: WHEN TO USE THE SERIALIZER?
      serializer = ORecordSerializerFactory.instance().getFormat(str);
    }

    _status = ORecordElement.STATUS.UNMARSHALLING;
    try {
      serializer.fromStream(content, this, null);
    } finally {
      _status = ORecordElement.STATUS.LOADED;
    }
  }

  /**
   * Returns the behavior of field() methods allowing access to the sub documents with dot notation ('.'). Default is true. Set it
   * to false if you allow to store properties with the dot.
   */
  public boolean isAllowChainedAccess() {
    return _allowChainedAccess;
  }

  /**
   * Change the behavior of field() methods allowing access to the sub documents with dot notation ('.'). Default is true. Set it to
   * false if you allow to store properties with the dot.
   */
  public ODocument setAllowChainedAccess(final boolean _allowChainedAccess) {
    this._allowChainedAccess = _allowChainedAccess;
    return this;
  }

  public void setClassNameIfExists(final String iClassName) {
    _immutableClazz = null;
    _immutableSchemaVersion = -1;

    _className = iClassName;

    if (iClassName == null) {
      return;
    }

    final ODatabaseDocument db = getDatabaseIfDefined();
    if (db != null) {
      final OClass _clazz = ((OMetadataInternal) db.getMetadata()).getImmutableSchemaSnapshot().getClass(iClassName);
      if (_clazz != null) {
        _className = _clazz.getName();
        convertFieldsToClass(_clazz);
      }
    }
  }

  public OClass getSchemaClass() {
    if (_className == null)
      fetchClassName();

    if (_className == null)
      return null;

    final ODatabaseDocument databaseRecord = getDatabaseIfDefined();
    if (databaseRecord != null)
      return databaseRecord.getMetadata().getSchema().getClass(_className);

    return null;
  }

  public String getClassName() {
    if (_className == null)
      fetchClassName();

    return _className;
  }

  public void setClassName(final String className) {
    _immutableClazz = null;
    _immutableSchemaVersion = -1;

    _className = className;

    if (className == null) {
      return;
    }

    final ODatabaseDocument db = getDatabaseIfDefined();
    if (db != null) {
      OMetadataInternal metadata = (OMetadataInternal) db.getMetadata();
      this._immutableClazz = (OImmutableClass) metadata.getImmutableSchemaSnapshot().getClass(className);
      OClass clazz;
      if (this._immutableClazz != null) {
        clazz = this._immutableClazz;
      } else {
        clazz = metadata.getSchema().getOrCreateClass(className);
      }
      if (clazz != null) {
        _className = clazz.getName();
        convertFieldsToClass(clazz);
      }
    }
  }

  /**
   * Validates the record following the declared constraints defined in schema such as mandatory, notNull, min, max, regexp, etc. If
   * the schema is not defined for the current class or there are not constraints then the validation is ignored.
   *
   * @throws OValidationException if the document breaks some validation constraints defined in the schema
   * @see OProperty
   */
  public void validate() throws OValidationException {
    checkForLoading();
    checkForFields();

    autoConvertValues();

    if (ODatabaseRecordThreadLocal.INSTANCE.isDefined() && !getDatabase().isValidationEnabled())
      return;

    final OImmutableClass immutableSchemaClass = getImmutableSchemaClass();
    if (immutableSchemaClass != null) {
      if (immutableSchemaClass.isStrictMode()) {
        // CHECK IF ALL FIELDS ARE DEFINED
        for (String f : fieldNames()) {
          if (immutableSchemaClass.getProperty(f) == null)
            throw new OValidationException(
                "Found additional field '" + f + "'. It cannot be added because the schema class '" + immutableSchemaClass.getName()
                    + "' is defined as STRICT");
        }
      }

      for (OProperty p : immutableSchemaClass.properties()) {
        validateField(this, (OImmutableProperty) p);
      }
    }
  }

  protected String toString(Set<ORecord> inspected) {
    if (inspected.contains(this))
      return "<recursion:rid=" + (_recordId != null ? _recordId : "null") + ">";
    else
      inspected.add(this);

    final boolean saveDirtyStatus = _dirty;
    final boolean oldUpdateContent = _contentChanged;

    try {
      final StringBuilder buffer = new StringBuilder(128);

      checkForFields();

      final ODatabaseDocument db = getDatabaseIfDefined();
      if (db != null && !db.isClosed()) {
        final String clsName = getClassName();
        if (clsName != null)
          buffer.append(clsName);
      }

      if (_recordId != null) {
        if (_recordId.isValid())
          buffer.append(_recordId);
      }

      boolean first = true;
      for (Entry<String, ODocumentEntry> f : _fields.entrySet()) {
        buffer.append(first ? '{' : ',');
        buffer.append(f.getKey());
        buffer.append(':');
        if (f.getValue().value == null)
          buffer.append("null");
        else if (f.getValue().value instanceof Collection<?> || f.getValue().value instanceof Map<?, ?> || f.getValue().value
            .getClass().isArray()) {
          buffer.append('[');
          buffer.append(OMultiValue.getSize(f.getValue().value));
          buffer.append(']');
        } else if (f.getValue().value instanceof ORecord) {
          final ORecord record = (ORecord) f.getValue().value;

          if (record.getIdentity().isValid())
            record.getIdentity().toString(buffer);
          else if (record instanceof ODocument)
            buffer.append(((ODocument) record).toString(inspected));
          else
            buffer.append(record.toString());
        } else
          buffer.append(f.getValue().value);

        if (first)
          first = false;
      }
      if (!first)
        buffer.append('}');

      if (_recordId != null && _recordId.isValid()) {
        buffer.append(" v");
        buffer.append(_recordVersion);
      }

      return buffer.toString();
    } finally {
      _dirty = saveDirtyStatus;
      _contentChanged = oldUpdateContent;
    }
  }

  protected ODocument mergeMap(final Map<String, ODocumentEntry> iOther, final boolean iUpdateOnlyMode,
      boolean iMergeSingleItemsOfMultiValueFields) {
    checkForLoading();
    checkForFields();

    _source = null;

    for (String f : iOther.keySet()) {
      ODocumentEntry docEntry = iOther.get(f);
      if (!docEntry.exist()) {
        continue;
      }
      final Object otherValue = docEntry.value;

      ODocumentEntry curValue = _fields.get(f);

      if (curValue != null && curValue.exist()) {
        final Object value = curValue.value;
        if (iMergeSingleItemsOfMultiValueFields) {
          if (value instanceof Map<?, ?>) {
            final Map<String, Object> map = (Map<String, Object>) value;
            final Map<String, Object> otherMap = (Map<String, Object>) otherValue;

            for (Entry<String, Object> entry : otherMap.entrySet()) {
              map.put(entry.getKey(), entry.getValue());
            }
            continue;
          } else if (OMultiValue.isMultiValue(value) && !(value instanceof ORidBag)) {
            for (Object item : OMultiValue.getMultiValueIterable(otherValue)) {
              if (!OMultiValue.contains(value, item))
                OMultiValue.add(value, item);
            }

            // JUMP RAW REPLACE
            continue;
          }
        }
        boolean bagsMerged = false;
        if (value instanceof ORidBag && otherValue instanceof ORidBag)
          bagsMerged = ((ORidBag) value).tryMerge((ORidBag) otherValue, iMergeSingleItemsOfMultiValueFields);

        if (!bagsMerged && (value != null && !value.equals(otherValue)) || (value == null && otherValue != null)) {
          field(f, otherValue);
        }
      } else {
        field(f, otherValue);
      }
    }

    if (!iUpdateOnlyMode) {
      // REMOVE PROPERTIES NOT FOUND IN OTHER DOC
      for (String f : fieldNames())
        if (!iOther.containsKey(f) || !iOther.get(f).exist())
          removeField(f);
    }

    return this;
  }

  @Override
  protected ORecordAbstract fill(final ORID iRid, final int iVersion, final byte[] iBuffer, final boolean iDirty) {
    _schema = null;
    fetchSchemaIfCan();
    return super.fill(iRid, iVersion, iBuffer, iDirty);
  }

  @Override
  protected void clearSource() {
    super.clearSource();
    _schema = null;
  }

  protected OGlobalProperty getGlobalPropertyById(int id) {
    if (_schema == null) {
      OMetadataInternal metadata = getDatabase().getMetadata();
      _schema = metadata.getImmutableSchemaSnapshot();
    }
    OGlobalProperty prop = _schema.getGlobalPropertyById(id);
    if (prop == null) {
      ODatabaseDocument db = getDatabase();
      if (db == null || db.isClosed())
        throw new ODatabaseException(
            "Cannot unmarshall the document because no database is active, use detach for use the document outside the database session scope");
      OMetadataInternal metadata = (OMetadataInternal) db.getMetadata();
      if (metadata.getImmutableSchemaSnapshot() != null)
        metadata.clearThreadLocalSchemaSnapshot();
      metadata.reload();
      metadata.makeThreadLocalSchemaSnapshot();
      _schema = metadata.getImmutableSchemaSnapshot();
      prop = _schema.getGlobalPropertyById(id);
    }
    return prop;
  }

  protected void fillClassIfNeed(final String iClassName) {
    if (this._className == null) {
      _immutableClazz = null;
      _immutableSchemaVersion = -1;
      _className = iClassName;
    }

  }

  protected OImmutableClass getImmutableSchemaClass() {
    if (_immutableClazz == null) {
      if (_className == null)
        fetchClassName();
      final ODatabaseDocument databaseRecord = getDatabaseIfDefined();

      if (databaseRecord != null && !databaseRecord.isClosed()) {
        final OSchema immutableSchema = ((OMetadataInternal) databaseRecord.getMetadata()).getImmutableSchemaSnapshot();
        if (immutableSchema == null)
          return null;
        _immutableSchemaVersion = immutableSchema.getVersion();
        _immutableClazz = (OImmutableClass) immutableSchema.getClass(_className);
      }
    }

    return _immutableClazz;
  }

  protected void rawField(final String iFieldName, final Object iFieldValue, final OType iFieldType) {
    if (_fields == null)
      _fields = _ordered ? new LinkedHashMap<>() : new HashMap<>();

    ODocumentEntry entry = getOrCreate(iFieldName);
    removeCollectionChangeListener(entry, entry.value);
    entry.value = iFieldValue;
    entry.type = iFieldType;
    addCollectionChangeListener(entry);
    if (iFieldValue instanceof OIdentifiable && !((OIdentifiable) iFieldValue).getIdentity().isPersistent())
      track((OIdentifiable) iFieldValue);
  }

  protected ODocumentEntry getOrCreate(String key) {
    ODocumentEntry entry = _fields.get(key);
    if (entry == null) {
      entry = new ODocumentEntry();
      _fieldSize++;
      _fields.put(key, entry);
    }
    return entry;
  }

  protected boolean rawContainsField(final String iFiledName) {
    return _fields != null && _fields.containsKey(iFiledName);
  }

  protected void autoConvertValues() {
    OClass clazz = getImmutableSchemaClass();
    if (clazz != null) {
      for (OProperty prop : clazz.properties()) {
        OType type = prop.getType();
        OType linkedType = prop.getLinkedType();
        OClass linkedClass = prop.getLinkedClass();
        if (type == OType.EMBEDDED && linkedClass != null) {
          convertToEmbeddedType(prop);
          continue;
        }
        if (linkedType == null)
          continue;
        final ODocumentEntry entry = _fields.get(prop.getName());
        if (entry == null)
          continue;
        if (!entry.created && !entry.changed)
          continue;
        Object value = entry.value;
        if (value == null)
          continue;
        try {
          if (type == OType.EMBEDDEDLIST) {
            OTrackedList<Object> list = new OTrackedList<>(this);
            Collection<Object> values = (Collection<Object>) value;
            for (Object object : values) {
              list.add(OType.convert(object, linkedType.getDefaultJavaType()));
            }
            entry.value = list;
            replaceListenerOnAutoconvert(entry, value);
          } else if (type == OType.EMBEDDEDMAP) {
            Map<Object, Object> map = new OTrackedMap<>(this);
            Map<Object, Object> values = (Map<Object, Object>) value;
            for (Entry<Object, Object> object : values.entrySet()) {
              map.put(object.getKey(), OType.convert(object.getValue(), linkedType.getDefaultJavaType()));
            }
            entry.value = map;
            replaceListenerOnAutoconvert(entry, value);
          } else if (type == OType.EMBEDDEDSET) {
            Set<Object> set = new OTrackedSet<>(this);
            Collection<Object> values = (Collection<Object>) value;
            for (Object object : values) {
              set.add(OType.convert(object, linkedType.getDefaultJavaType()));
            }
            entry.value = set;
            replaceListenerOnAutoconvert(entry, value);
          }
        } catch (Exception e) {
          throw OException
              .wrapException(new OValidationException("impossible to convert value of field \"" + prop.getName() + "\""), e);
        }
      }
    }
  }

  private void convertToEmbeddedType(OProperty prop) {
    final ODocumentEntry entry = _fields.get(prop.getName());
    OClass linkedClass = prop.getLinkedClass();
    if (entry == null || linkedClass == null) {
      return;
    }
    if (!entry.created && !entry.changed) {
      return;
    }
    Object value = entry.value;
    if (value == null) {
      return;
    }
    try {
      if (value instanceof ODocument) {
        OClass docClass = ((ODocument) value).getSchemaClass();
        if (docClass == null) {
          ((ODocument) value).setClass(linkedClass);
        } else if (!docClass.isSubClassOf(linkedClass)) {
          throw new OValidationException(
              "impossible to convert value of field \"" + prop.getName() + "\", incompatible with " + linkedClass);
        }
      } else if (value instanceof Map) {
        removeCollectionChangeListener(entry, value);
        ODocument newValue = new ODocument(linkedClass);
        newValue.fromMap((Map) value);
        entry.value = newValue;
        newValue.addOwner(this);
      } else {
        throw new OValidationException("impossible to convert value of field \"" + prop.getName() + "\"");
      }

    } catch (Exception e) {
      throw OException
          .wrapException(new OValidationException("impossible to convert value of field \"" + prop.getName() + "\""), e);
    }
  }

  private void replaceListenerOnAutoconvert(final ODocumentEntry entry, Object oldValue) {
    if (entry.changeListener != null) {
      // A listener was there, remove on the old value add it to the new value.
      final OTrackedMultiValue<Object, Object> oldMultiValue = (OTrackedMultiValue<Object, Object>) oldValue;
      oldMultiValue.removeRecordChangeListener(entry.changeListener);
      ((OTrackedMultiValue<Object, Object>) entry.value).addChangeListener(entry.changeListener);
    } else {
      // no listener was there add it only to the new value
      final OSimpleMultiValueChangeListener<Object, Object> listener = new OSimpleMultiValueChangeListener<>(this, entry);
      ((OTrackedMultiValue<Object, Object>) entry.value).addChangeListener(listener);
      entry.changeListener = listener;
    }
  }

  protected byte[] toStream(final boolean iOnlyDelta) {
    STATUS prev = _status;
    _status = STATUS.MARSHALLING;
    try {
      if (_source == null)
        _source = _recordFormat.toStream(this, iOnlyDelta);
    } finally {
      _status = prev;
    }
    invokeListenerEvent(ORecordListener.EVENT.MARSHALL);

    return _source;
  }

  /**
   * Internal.
   */
  protected byte getRecordType() {
    return RECORD_TYPE;
  }

  /**
   * Internal.
   */
  protected void addOwner(final ORecordElement iOwner) {
    if (iOwner == null)
      return;
    if (_owners == null) {
      _owners = new ArrayList<>();
      if (_dirtyManager != null && this.getIdentity().isNew())
        _dirtyManager.removeNew(this);
    }

    boolean found = false;
    Iterator<WeakReference<ORecordElement>> ref = _owners.iterator();
    while (ref.hasNext()) {
      final ORecordElement e = ref.next().get();
      if (e == iOwner) {
        found = true;
        break;
      } else if (e == null)
        ref.remove();
    }

    if (!found)
      this._owners.add(new WeakReference<>(iOwner));

  }

  protected void removeOwner(final ORecordElement iRecordElement) {
    if (_owners != null) {
      // PROPAGATES TO THE OWNER
      ORecordElement e;
      for (int i = 0; i < _owners.size(); ++i) {
        e = _owners.get(i).get();
        if (e == iRecordElement) {
          _owners.remove(i);
          break;
        }
      }
    }
  }

  /**
   * Converts all non-tracked collections implementations contained in document fields to tracked ones.
   *
   * @see OTrackedMultiValue
   */
  protected void convertAllMultiValuesToTrackedVersions() {
    if (_fields == null)
      return;
    for (Map.Entry<String, ODocumentEntry> fieldEntry : _fields.entrySet()) {
      final Object fieldValue = fieldEntry.getValue().value;
      if (!(fieldValue instanceof Collection<?>) && !(fieldValue instanceof Map<?, ?>) && !(fieldValue instanceof ODocument))
        continue;
      if (addCollectionChangeListener(fieldEntry.getValue())) {
        if (fieldEntry.getValue().timeLine != null && !fieldEntry.getValue().timeLine.getMultiValueChangeEvents().isEmpty()) {
          checkTimelineTrackable(fieldEntry.getValue().timeLine, (OTrackedMultiValue) fieldEntry.getValue().value);
        }
        continue;
      }

      if (fieldValue instanceof ODocument && ((ODocument) fieldValue).isEmbedded()) {
        ((ODocument) fieldValue).convertAllMultiValuesToTrackedVersions();
        continue;
      }

      OType fieldType = fieldEntry.getValue().type;
      if (fieldType == null) {
        OClass _clazz = getImmutableSchemaClass();
        if (_clazz != null) {
          final OProperty prop = _clazz.getProperty(fieldEntry.getKey());
          fieldType = prop != null ? prop.getType() : null;
        }
      }
      if (fieldType == null)
        fieldType = OType.getTypeByValue(fieldValue);

      Object newValue = null;
      switch (fieldType) {
      case EMBEDDEDLIST:
        if (fieldValue instanceof List<?>) {
          newValue = new OTrackedList<>(this);
          fillTrackedCollection((Collection<Object>) newValue, (Collection<Object>) fieldValue);
        }
        break;
      case EMBEDDEDSET:
        if (fieldValue instanceof Set<?>) {
          newValue = new OTrackedSet<>(this);
          fillTrackedCollection((Collection<Object>) newValue, (Collection<Object>) fieldValue);
        }
        break;
      case EMBEDDEDMAP:
        if (fieldValue instanceof Map<?, ?>) {
          newValue = new OTrackedMap<>(this);
          fillTrackedMap((Map<Object, Object>) newValue, (Map<Object, Object>) fieldValue);
        }
        break;
      case LINKLIST:
        if (fieldValue instanceof List<?>)
          newValue = new ORecordLazyList(this, (Collection<OIdentifiable>) fieldValue);
        break;
      case LINKSET:
        if (fieldValue instanceof Set<?>)
          newValue = new ORecordLazySet(this, (Collection<OIdentifiable>) fieldValue);
        break;
      case LINKMAP:
        if (fieldValue instanceof Map<?, ?>)
          newValue = new ORecordLazyMap(this, (Map<Object, OIdentifiable>) fieldValue);
        break;
      case LINKBAG:
        if (fieldValue instanceof Collection<?>) {
          ORidBag bag = new ORidBag();
          bag.setOwner(this);
          bag.addAll((Collection<OIdentifiable>) fieldValue);
          newValue = bag;
        }
        break;
      default:
        break;
      }

      if (newValue != null) {
        addCollectionChangeListener(fieldEntry.getValue());
        fieldEntry.getValue().value = newValue;
        if (fieldType == OType.LINKSET || fieldType == OType.LINKLIST) {
          boolean pre = ((OAutoConvertToRecord) newValue).isAutoConvertToRecord();
          ((OAutoConvertToRecord) newValue).setAutoConvertToRecord(false);
          for (OIdentifiable rec : (Collection<OIdentifiable>) newValue) {
            if (rec instanceof ODocument)
              ((ODocument) rec).convertAllMultiValuesToTrackedVersions();
          }
          ((OAutoConvertToRecord) newValue).setAutoConvertToRecord(pre);
        } else if (fieldType == OType.LINKMAP) {
          boolean pre = ((OAutoConvertToRecord) newValue).isAutoConvertToRecord();
          ((OAutoConvertToRecord) newValue).setAutoConvertToRecord(false);
          for (OIdentifiable rec : (Collection<OIdentifiable>) ((Map<?, ?>) newValue).values()) {
            if (rec instanceof ODocument)
              ((ODocument) rec).convertAllMultiValuesToTrackedVersions();
          }
          ((OAutoConvertToRecord) newValue).setAutoConvertToRecord(pre);
        }
      }
    }

  }

  private void checkTimelineTrackable(OMultiValueChangeTimeLine<Object, Object> timeLine, OTrackedMultiValue origin) {
    List<OMultiValueChangeEvent<Object, Object>> events = timeLine.getMultiValueChangeEvents();
    for (OMultiValueChangeEvent<Object, Object> event : events) {
      Object value = event.getValue();
      if (event.getChangeType() == OMultiValueChangeEvent.OChangeType.ADD && !(value instanceof OTrackedMultiValue)) {
        if (value instanceof Collection) {
          Collection<Object> newCollection = value instanceof List ? new OTrackedList<>(this) : new OTrackedSet<>(this);
          fillTrackedCollection(newCollection, (Collection<Object>) value);
          origin.replace(event, newCollection);
        } else if (value instanceof Map) {
          Map<Object, Object> newMap = new OTrackedMap<>(this);
          fillTrackedMap(newMap, (Map<Object, Object>) value);
          origin.replace(event, newMap);
        }
      } else if (event.getChangeType() == OMultiValueChangeEvent.OChangeType.NESTED) {
        OMultiValueChangeTimeLine nestedTimeline = ((ONestedMultiValueChangeEvent) event).getTimeLine();
        if (nestedTimeline != null)
          checkTimelineTrackable(nestedTimeline, (OTrackedMultiValue) value);
      }
    }
  }

  private void fillTrackedCollection(Collection<Object> dest, Collection<Object> source) {
    for (Object cur : source) {
      if (cur instanceof ODocument)
        ((ODocument) cur).convertAllMultiValuesToTrackedVersions();
      else if (cur instanceof List) {
        List<Object> newList = new OTrackedList<>(this);
        fillTrackedCollection(newList, (Collection<Object>) cur);
        cur = newList;
      } else if (cur instanceof Set) {
        Set<Object> newSet = new OTrackedSet<>(this);
        fillTrackedCollection(newSet, (Collection<Object>) cur);
        cur = newSet;
      } else if (cur instanceof Map) {
        Map<Object, Object> newMap = new OTrackedMap<>(this);
        fillTrackedMap(newMap, (Map<Object, Object>) cur);
        cur = newMap;
      }
      dest.add(cur);
    }
  }

  private void fillTrackedMap(Map<Object, Object> dest, Map<Object, Object> source) {
    for (Entry<Object, Object> cur : source.entrySet()) {
      Object value = cur.getValue();
      if (value instanceof ODocument)
        ((ODocument) value).convertAllMultiValuesToTrackedVersions();
      else if (cur.getValue() instanceof List) {
        List<Object> newList = new OTrackedList<>(this);
        fillTrackedCollection(newList, (Collection<Object>) value);
        value = newList;
      } else if (value instanceof Set) {
        Set<Object> newSet = new OTrackedSet<>(this);
        fillTrackedCollection(newSet, (Collection<Object>) value);
        value = newSet;
      } else if (value instanceof Map) {
        Map<Object, Object> newMap = new OTrackedMap<>(this);
        fillTrackedMap(newMap, (Map<Object, Object>) value);
        value = newMap;
      }
      dest.put(cur.getKey(), value);
    }
  }

  protected void internalReset() {
    removeAllCollectionChangeListeners();

    if (_fields != null)
      _fields.clear();
    _fieldSize = 0;

  }

  protected boolean checkForFields(final String... iFields) {
    if (_fields == null)
      _fields = _ordered ? new LinkedHashMap<>() : new HashMap<>();

    if (_status == ORecordElement.STATUS.LOADED && _source != null)
      // POPULATE FIELDS LAZY
      return deserializeFields(iFields);

    return true;
  }

  /**
   * Internal.
   */
  @Override
  protected void setup() {
    super.setup();

    final ODatabaseDocumentInternal db = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    if (db != null)
      _recordFormat = db.getSerializer();

    if (_recordFormat == null)
      // GET THE DEFAULT ONE
      _recordFormat = ODatabaseDocumentTx.getDefaultSerializer();
  }

  protected String checkFieldName(final String iFieldName) {
    final Character c = OSchemaShared.checkFieldNameIfValid(iFieldName);
    if (c != null)
      throw new IllegalArgumentException("Invalid field name '" + iFieldName + "'. Character '" + c + "' is invalid");

    return iFieldName;
  }

  protected void setClass(final OClass iClass) {
    if (iClass != null && iClass.isAbstract())
      throw new OSchemaException("Cannot create a document of the abstract class '" + iClass + "'");

    if (iClass == null)
      _className = null;
    else
      _className = iClass.getName();

    _immutableClazz = null;
    _immutableSchemaVersion = -1;
    if (iClass != null)
      convertFieldsToClass(iClass);
  }

  protected Set<Entry<String, ODocumentEntry>> getRawEntries() {
    checkForFields();
    return _fields == null ? new HashSet<>() : _fields.entrySet();
  }

  private void fetchSchemaIfCan() {
    if (_schema == null) {
      ODatabaseDocumentInternal db = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
      if (db != null && !db.isClosed()) {
        OMetadataInternal metadata = db.getMetadata();
        _schema = metadata.getImmutableSchemaSnapshot();
      }
    }
  }

  private void fetchClassName() {
    final ODatabaseDocumentInternal database = getDatabaseIfDefinedInternal();
    if (database != null && database.getStorageVersions() != null) {
      if (_recordId.getClusterId() < 0) {
        checkForLoading();
        checkForFields(ODocumentHelper.ATTRIBUTE_CLASS);
      } else {
        final OSchema schema = database.getMetadata().getImmutableSchemaSnapshot();
        if (schema != null) {
          OClass _clazz = schema.getClassByClusterId(_recordId.getClusterId());
          if (_clazz != null)
            _className = _clazz.getName();
        }
      }
    } else {
      // CLASS NOT FOUND: CHECK IF NEED LOADING AND UNMARSHALLING
      checkForLoading();
      checkForFields(ODocumentHelper.ATTRIBUTE_CLASS);
    }
  }

  protected void autoConvertFieldsToClass(final ODatabaseDocumentInternal database) {
    if (_className != null) {
      OClass klazz = database.getMetadata().getImmutableSchemaSnapshot().getClass(_className);
      if (klazz != null)
        convertFieldsToClass(klazz);
    }
  }

  /**
   * Checks and convert the field of the document matching the types specified by the class.
   **/
  private void convertFieldsToClass(final OClass _clazz) {
    for (OProperty prop : _clazz.properties()) {
      ODocumentEntry entry = _fields != null ? _fields.get(prop.getName()) : null;
      if (entry != null && entry.exist()) {
        if (entry.type == null || entry.type != prop.getType()) {
          field(prop.getName(), entry.value, prop.getType());
        }
      } else {
        String defValue = prop.getDefaultValue();
        if (defValue != null && defValue.length() > 0 && !containsField(prop.getName())) {
          Object curFieldValue = OSQLHelper.parseDefaultValue(this, defValue);
          Object fieldValue = ODocumentHelper.convertField(this, prop.getName(), prop.getType(), null, curFieldValue);
          rawField(prop.getName(), fieldValue, prop.getType());
        }
      }
    }
  }

  private OType deriveFieldType(String iFieldName, ODocumentEntry entry, OType[] iFieldType) {
    OType fieldType;

    if (iFieldType != null && iFieldType.length == 1) {
      entry.type = iFieldType[0];
      fieldType = iFieldType[0];
    } else
      fieldType = null;

    OClass _clazz = getImmutableSchemaClass();
    if (_clazz != null) {
      // SCHEMA-FULL?
      final OProperty prop = _clazz.getProperty(iFieldName);
      if (prop != null) {
        entry.property = prop;
        fieldType = prop.getType();
        if (fieldType != OType.ANY)
          entry.type = fieldType;
      }
    }
    return fieldType;
  }

  private boolean addCollectionChangeListener(final ODocumentEntry entry) {
    if (!(entry.value instanceof OTrackedMultiValue))
      return false;
    if (entry.changeListener == null) {
      final OSimpleMultiValueChangeListener<Object, Object> listener = new OSimpleMultiValueChangeListener<>(this, entry);
      ((OTrackedMultiValue<Object, Object>) entry.value).addChangeListener(listener);
      entry.changeListener = listener;
    }
    return true;
  }

  private void removeAllCollectionChangeListeners() {
    if (_fields == null)
      return;

    for (final Map.Entry<String, ODocumentEntry> field : _fields.entrySet()) {
      removeCollectionChangeListener(field.getValue(), field.getValue().value);
    }
  }

  private void addAllMultiValueChangeListeners() {
    if (_fields == null)
      return;

    for (final Map.Entry<String, ODocumentEntry> field : _fields.entrySet()) {
      addCollectionChangeListener(field.getValue());
    }
  }

  private void removeCollectionChangeListener(ODocumentEntry entry, Object fieldValue) {
    if (entry != null && entry.changeListener != null) {
      final OMultiValueChangeListener<Object, Object> changeListener = entry.changeListener;
      entry.changeListener = null;
      entry.timeLine = null;
      if (!(fieldValue instanceof OTrackedMultiValue))
        return;

      final OTrackedMultiValue<Object, Object> multiValue = (OTrackedMultiValue<Object, Object>) fieldValue;
      multiValue.removeRecordChangeListener(changeListener);
    }
  }

  protected void checkClass(ODatabaseDocumentInternal database) {
    if (_className == null)
      fetchClassName();

    final OSchema immutableSchema = database.getMetadata().getImmutableSchemaSnapshot();
    if (immutableSchema == null)
      return;

    if (_immutableClazz == null) {
      _immutableSchemaVersion = immutableSchema.getVersion();
      _immutableClazz = (OImmutableClass) immutableSchema.getClass(_className);
    } else {
      if (_immutableSchemaVersion < immutableSchema.getVersion()) {
        _immutableSchemaVersion = immutableSchema.getVersion();
        _immutableClazz = (OImmutableClass) immutableSchema.getClass(_className);
      }
    }

  }

  @Override
  protected void track(OIdentifiable id) {
    if (isTrackingChanges() && id.getIdentity().getClusterId() != -2)
      super.track(id);
  }

  @Override
  protected void unTrack(OIdentifiable id) {
    if (isTrackingChanges() && id.getIdentity().getClusterId() != -2)
      super.unTrack(id);
  }
}

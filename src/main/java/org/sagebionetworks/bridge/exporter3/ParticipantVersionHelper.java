package org.sagebionetworks.bridge.exporter3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.jcabi.aspects.Cacheable;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.PartialRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.json.DefaultObjectMapper;
import org.sagebionetworks.bridge.rest.model.DemographicResponse;
import org.sagebionetworks.bridge.rest.model.ParticipantVersion;
import org.sagebionetworks.bridge.synapse.SynapseHelper;
import org.sagebionetworks.bridge.workerPlatform.util.Constants;

/** Helper class that encapsulates exporting a single participant version. */
@Component
public class ParticipantVersionHelper {
    private static final Logger LOG = LoggerFactory.getLogger(ParticipantVersionHelper.class);

    // Participant Version table columns
    public static final String COLUMN_NAME_HEALTH_CODE = "healthCode";
    public static final String COLUMN_NAME_PARTICIPANT_VERSION = "participantVersion";
    public static final String COLUMN_NAME_CREATED_ON = "createdOn";
    public static final String COLUMN_NAME_MODIFIED_ON = "modifiedOn";
    public static final String COLUMN_NAME_DATA_GROUPS = "dataGroups";
    public static final String COLUMN_NAME_LANGUAGES = "languages";
    public static final String COLUMN_NAME_SHARING_SCOPE = "sharingScope";
    public static final String COLUMN_NAME_STUDY_MEMBERSHIPS = "studyMemberships";
    public static final String COLUMN_NAME_CLIENT_TIME_ZONE = "clientTimeZone";
    // Participant Version Demographics table columns
    public static final String COLUMN_NAME_APP_ID = "appId";
    public static final String COLUMN_NAME_STUDY_ID = "studyId";
    public static final String COLUMN_NAME_DEMOGRAPHIC_CATEGORY_NAME = "demographicCategoryName";
    public static final String COLUMN_NAME_DEMOGRAPHIC_VALUE = "demographicValue";
    public static final String COLUMN_NAME_DEMOGRAPHIC_UNITS = "demographicUnits";

    static final String EXT_ID_NONE = "<none>";

    private static final int MAX_LANGUAGE_LENGTH = 5;
    private static final int MAX_LANGUAGES = 10;

    private SynapseHelper synapseHelper;

    @Autowired
    public final void setSynapseHelper(SynapseHelper synapseHelper) {
        this.synapseHelper = synapseHelper;
    }

    /**
     * Exports a single participant version using the specified EX3.0 config. If this is the app-wide Synapse project,
     * leave studyId blank.
     */
    public PartialRow makeRowForParticipantVersion(String studyId, String participantVersionTableId,
            ParticipantVersion participantVersion) throws JsonProcessingException, SynapseException {
        Map<String, String> columnNameToId = getColumnNameToIdMap(participantVersionTableId);
        String healthCode = participantVersion.getHealthCode();
        Integer versionNum = participantVersion.getParticipantVersion();

        // Make this into a Synapse row set. Most of these values can't be null, but check anyway in case there's a bug
        // or unexpected input.
        // We don't need to sanitize any of the strings. All of these are either generated by the Server or have
        // already been validated by the server.
        Map<String, String> rowMap = new HashMap<>();
        if (participantVersion.getHealthCode() != null) {
            rowMap.put(columnNameToId.get(COLUMN_NAME_HEALTH_CODE), participantVersion.getHealthCode());
        }
        if (participantVersion.getParticipantVersion() != null) {
            rowMap.put(columnNameToId.get(COLUMN_NAME_PARTICIPANT_VERSION), participantVersion.getParticipantVersion()
                    .toString());
        }
        if (participantVersion.getCreatedOn() != null) {
            rowMap.put(columnNameToId.get(COLUMN_NAME_CREATED_ON), String.valueOf(participantVersion.getCreatedOn()
                    .getMillis()));
        }
        if (participantVersion.getModifiedOn() != null) {
            rowMap.put(columnNameToId.get(COLUMN_NAME_MODIFIED_ON), String.valueOf(participantVersion.getModifiedOn()
                    .getMillis()));
        }
        if (participantVersion.getDataGroups() != null) {
            // Order doesn't matter for data groups, so sort them alphabetically to get a "canonical" ordering.
            // This is serialized as a comma-delimited list. See BridgeServer2 Exporter3Service for more details.
            List<String> dataGroupCopy = new ArrayList<>(participantVersion.getDataGroups());
            Collections.sort(dataGroupCopy);
            rowMap.put(columnNameToId.get(COLUMN_NAME_DATA_GROUPS), Constants.COMMA_JOINER.join(dataGroupCopy));
        }
        if (participantVersion.getLanguages() != null) {
            List<String> languageList = participantVersion.getLanguages();

            // If we have more languages than the max, we truncate.
            if (languageList.size() > MAX_LANGUAGES) {
                LOG.warn("Truncating language list; healthcode " + healthCode + " version " + versionNum + " has " +
                        languageList.size() + " languages");
                languageList = languageList.subList(0, MAX_LANGUAGES);
            }

            // Truncate language length, so that an invalid language doesn't fail the entire row.
            int numLanguages = languageList.size();
            for (int i = 0; i < numLanguages; i++) {
                String language = languageList.get(i);
                if (language.length() > MAX_LANGUAGE_LENGTH) {
                    LOG.warn("Truncating language; healthcode " + healthCode + " version " + versionNum +
                            " has invalid language " + language);
                    languageList.set(i, language.substring(0, MAX_LANGUAGE_LENGTH));
                }
            }

            // Order *does* matter for languages. Also, the format for a string list in Synapse appears to be a JSON
            // array.
            String serializedLanguages = DefaultObjectMapper.INSTANCE.writeValueAsString(languageList);
            rowMap.put(columnNameToId.get(COLUMN_NAME_LANGUAGES), serializedLanguages);
        }
        if (participantVersion.getSharingScope() != null) {
            rowMap.put(columnNameToId.get(COLUMN_NAME_SHARING_SCOPE), participantVersion.getSharingScope().getValue());
        }
        // serializeStudyMemberships is null-safe, and it converts to null if there are no values.
        // participantVersion.getStudyMemberships() comes from account.getActiveEnrollments(), which excludes
        // withdrawn enrollments.
        String serializedStudyMemberships = serializeStudyMemberships(studyId,
                participantVersion.getStudyMemberships());
        if (serializedStudyMemberships != null) {
            rowMap.put(columnNameToId.get(COLUMN_NAME_STUDY_MEMBERSHIPS), serializedStudyMemberships);
        }
        if (participantVersion.getTimeZone() != null) {
            rowMap.put(columnNameToId.get(COLUMN_NAME_CLIENT_TIME_ZONE), participantVersion.getTimeZone());
        }
        PartialRow row = new PartialRow();
        row.setValues(rowMap);
        return row;
    }

    public List<PartialRow> makeRowsForParticipantVersionDemographics(String appId, String studyId, String participantVersionDemographicsTableId, ParticipantVersion participantVersion) throws SynapseException {
        Map<String, String> columnNameToId = getColumnNameToIdMap(participantVersionDemographicsTableId);
        String healthCode = participantVersion.getHealthCode();
        Integer versionNum = participantVersion.getParticipantVersion();
        if (healthCode == null || versionNum == null) {
            // this table is not useful without the ability to be joined with the main participant versions table
            return new ArrayList<>();
        }

        List<PartialRow> rows = new ArrayList<>();
        if (participantVersion.getAppDemographics() != null) {
            for (Map.Entry<String, DemographicResponse> entry : participantVersion.getAppDemographics().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    // don't bother saving null categoryName or demographic
                    continue;
                }
                String categoryName = entry.getKey();
                DemographicResponse demographic = entry.getValue();
                String units = demographic.getUnits();
                for (String value : demographic.getValues()) {
                    Map<String, String> rowMap = new HashMap<>();
                    rowMap.put(columnNameToId.get(COLUMN_NAME_HEALTH_CODE), participantVersion.getHealthCode());
                    rowMap.put(columnNameToId.get(COLUMN_NAME_PARTICIPANT_VERSION), participantVersion.getParticipantVersion()
                            .toString());
                    rowMap.put(columnNameToId.get(COLUMN_NAME_DEMOGRAPHIC_CATEGORY_NAME), categoryName);
                    rowMap.put(columnNameToId.get(COLUMN_NAME_DEMOGRAPHIC_VALUE), value);
                    if (units != null) {
                        rowMap.put(columnNameToId.get(COLUMN_NAME_DEMOGRAPHIC_UNITS), units);
                    }
                    PartialRow row = new PartialRow();
                    row.setValues(rowMap);
                    rows.add(row);
                }
            }
        }

        return rows;
    }

    /**
     * This creates a map that maps column names to column IDs. Since this requires a network call and a bit of
     * computation, we cache it. This should never change, so we cache it forever.
     */
    @Cacheable(forever = true)
    private Map<String, String> getColumnNameToIdMap(String tableId) throws SynapseException {
        List<ColumnModel> columnModelList = synapseHelper.getColumnModelsForTableWithRetry(tableId);
        Map<String, String> columnNameToId = new HashMap<>();
        for (ColumnModel columnModel : columnModelList) {
            columnNameToId.put(columnModel.getName(), columnModel.getId());
        }
        return columnNameToId;
    }

    /**
     * This method serializes study memberships into a string. Study memberships are a map where the key is the
     * study ID and the value is the external ID, or "<none>" if not present. This is serialized into a form that
     * looks like: "|studyA=ext-A|studyB=|studyC=ext-C|" (Assuming studyB has no external ID.)
     *
     * If a study ID filter is specified, we serialize only that one study's memberships.
     */
    private static String serializeStudyMemberships(String studyIdFilter, Map<String, String> studyMemberships) {
        if (studyMemberships == null || studyMemberships.isEmpty()) {
            return null;
        }

        List<String> studyIdList;
        if (studyIdFilter != null) {
            // Just the one study.
            studyIdList = ImmutableList.of(studyIdFilter);
        } else {
            // Get the study IDs in alphabetical order, so that it's easier to test.
            studyIdList = new ArrayList<>(studyMemberships.keySet());
            Collections.sort(studyIdList);
        }

        List<String> pairs = new ArrayList<>();
        for (String studyId : studyIdList) {
            String extId = studyMemberships.get(studyId);
            String value = EXT_ID_NONE.equals(extId) ? "" : extId;
            pairs.add(studyId + "=" + value);
        }
        Collections.sort(pairs);
        return "|" + Constants.PIPE_JOINER.join(pairs) + "|";
    }
}

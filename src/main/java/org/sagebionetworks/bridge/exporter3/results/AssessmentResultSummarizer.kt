package org.sagebionetworks.bridge.exporter3.results

import org.sagebionetworks.assessmentmodel.AnswerColumn
import org.sagebionetworks.assessmentmodel.AssessmentResult
import org.sagebionetworks.assessmentmodel.serialization.Serialization
import org.sagebionetworks.assessmentmodel.toFlatAnswers
import org.sagebionetworks.assessmentmodel.toFlatAnswersDefinition
import org.sagebionetworks.bridge.rest.RestUtils
import org.sagebionetworks.bridge.rest.model.Assessment
import org.sagebionetworks.bridge.rest.model.AssessmentConfig
import org.slf4j.LoggerFactory

class AssessmentResultSummarizer(private val assessment: Assessment, private val assessmentConfig: AssessmentConfig): AssessmentSummarizer {

    init {
        assert(assessment.frameworkIdentifier == FRAMEWORK_IDENTIFIER)
    }

    override val resultFilename: String
        get() = "assessmentResult.json"

    override fun canSummarize(assessment: Assessment): Boolean {
        return assessment.frameworkIdentifier == "health.bridgedigital.assessment"
    }

    /**
     * Returns a flattened map of results for a survey where the key is the column name and the value is a string
     * representation of the result.
     */
    override fun summarizeResults(resultJson: String): Map<String, String> {
        val assessmentResult: AssessmentResult = Serialization.JsonCoder.default.decodeFromString(resultJson)
        val answers = assessmentResult.toFlatAnswers()
        val columnNames = getColumnNames()
        for (column in answers.keys) {
            if (!columnNames.contains(column)) {
                // TODO: How should we best log any unexpected columns? -nbrown 11/2/23
                LOG.debug("Unexpected column: " + column + " when summarizing results for assessment: " + assessment.identifier)
            }
        }

        return answers
    }

    override fun getColumnNames(): List<String> {
        if (!canSummarize(assessment)) {
            return listOf()
        }
        return getSurveyColumns().map { it.columnName }
    }

    /**
     * Returns a flattened list of columns for a survey. [AnswerColumn] contains a column name and data type for
     * the result.
     */
    fun getSurveyColumns() : List<AnswerColumn> {
        if (assessmentConfig.config != null) {
            val configString = RestUtils.toJSON(assessmentConfig.config).asString;
            val assessment: org.sagebionetworks.assessmentmodel.Assessment = Serialization.JsonCoder.default.decodeFromString(configString)
            return assessment.toFlatAnswersDefinition()
        } else {
            return listOf()
        }
    }

    companion object {
        const val FRAMEWORK_IDENTIFIER = "health.bridgedigital.assessment"
        private val LOG = LoggerFactory.getLogger(AssessmentResultSummarizer::class.java)
    }

}
package org.sitenv.referenceccda.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sitenv.referenceccda.dto.ValidationResultsDto;
import org.sitenv.referenceccda.dto.ValidationResultsMetaData;
import org.sitenv.referenceccda.validators.RefCCDAValidationResult;
import org.sitenv.referenceccda.validators.content.ReferenceContentValidator;
import org.sitenv.referenceccda.validators.schema.CCDATypes;
import org.sitenv.referenceccda.validators.schema.ReferenceCCDAValidator;
import org.sitenv.referenceccda.validators.schema.ValidationObjectives;
import org.sitenv.referenceccda.validators.vocabulary.VocabularyCCDAValidator;
import org.sitenv.vocabularies.constants.VocabularyConstants;
import org.sitenv.vocabularies.constants.VocabularyConstants.SeverityLevel;
import org.sitenv.vocabularies.validation.dto.GlobalCodeValidatorResults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

@Service
public class ReferenceCCDAValidationService {
	private static Logger logger = LoggerFactory.getLogger(ReferenceCCDAValidationService.class);

	private ReferenceCCDAValidator referenceCCDAValidator;
	private VocabularyCCDAValidator vocabularyCCDAValidator;
	private ReferenceContentValidator goldMatchingValidator;

	private static final String ERROR_GENERAL_PREFIX = "The service has encountered ";
	private static final String ERROR_PARSING_PREFIX = ERROR_GENERAL_PREFIX + "an error parsing the document. ";
	private static final String ERROR_FOLLOWING_ERROR_POSTFIX = "the following error: ";
	private static final String ERROR_IO_EXCEPTION = ERROR_GENERAL_PREFIX + "the following input/output error: ";
	private static final String ERROR_CLASS_CAST_EXCEPTION = ERROR_PARSING_PREFIX
			+ "Please verify the document is valid against schema and " + "contains a v3 namespace definition: ";
	private static final String ERROR_SAX_PARSE_EXCEPTION = ERROR_PARSING_PREFIX
			+ "Please verify the document does not contain in-line XSL styling and/or address "
			+ ERROR_FOLLOWING_ERROR_POSTFIX;
	private static final String ERROR_GENERIC_EXCEPTION = ERROR_GENERAL_PREFIX + ERROR_FOLLOWING_ERROR_POSTFIX;

	@Autowired
	public ReferenceCCDAValidationService(ReferenceCCDAValidator referenceCCDAValidator,
			VocabularyCCDAValidator vocabularyCCDAValidator, ReferenceContentValidator goldValidator) {
		this.referenceCCDAValidator = referenceCCDAValidator;
		this.vocabularyCCDAValidator = vocabularyCCDAValidator;
		this.goldMatchingValidator = goldValidator;
	}

	public ValidationResultsDto validateCCDA(String validationObjective, String referenceFileName,
			MultipartFile ccdaFile) {
		return validateCCDAImplementation(validationObjective, referenceFileName, ccdaFile, 
				false, false, false, false,
				VocabularyConstants.Config.DEFAULT, SeverityLevel.INFO);
	}

	public ValidationResultsDto validateCCDA(String validationObjective, String referenceFileName,
			MultipartFile ccdaFile, String vocabularyConfig) {
		return validateCCDAImplementation(validationObjective, referenceFileName, ccdaFile, 
				false, false, false, false,
				vocabularyConfig, SeverityLevel.INFO);
	}

	public ValidationResultsDto validateCCDA(String validationObjective, String referenceFileName,
			MultipartFile ccdaFile, boolean curesUpdate, boolean svap2022, boolean svap2023, boolean uscdiv4, 
			String vocabularyConfig, SeverityLevel severityLevel) {
		return validateCCDAImplementation(validationObjective, referenceFileName, ccdaFile, 
				curesUpdate, svap2022, svap2023, uscdiv4,
				vocabularyConfig, severityLevel);
	}

	private ValidationResultsDto validateCCDAImplementation(String validationObjective, String referenceFileName,
			MultipartFile ccdaFile, boolean curesUpdate, boolean svap2022, boolean svap2023, boolean uscdiv4,
			String vocabularyConfig, SeverityLevel severityLevel) {
		ValidationResultsDto resultsDto = new ValidationResultsDto();
		ValidationResultsMetaData resultsMetaData = new ValidationResultsMetaData();
		List<RefCCDAValidationResult> validatorResults = new ArrayList<>();
		try {
			validatorResults = runValidators(validationObjective, referenceFileName, ccdaFile, 
					curesUpdate, svap2022, svap2023, uscdiv4,
					vocabularyConfig, severityLevel);
			resultsMetaData = buildValidationMedata(validatorResults, validationObjective, severityLevel);
			resultsMetaData.setCcdaFileName(ccdaFile.getOriginalFilename());
			resultsMetaData.setCcdaFileContents(new String(ccdaFile.getBytes()));
		} catch (IOException ioE) {
			processValidateCCDAException(resultsMetaData, ERROR_IO_EXCEPTION, validationObjective, ioE);
		} catch (SAXException saxE) {
			processValidateCCDAException(resultsMetaData, ERROR_SAX_PARSE_EXCEPTION, validationObjective, saxE);
		} catch (ClassCastException ccE) {
			processValidateCCDAException(resultsMetaData, ERROR_CLASS_CAST_EXCEPTION, validationObjective, ccE);
		} catch (Exception catchAllE) {
			processValidateCCDAException(resultsMetaData, ERROR_GENERIC_EXCEPTION, validationObjective, catchAllE);
		}
		resultsDto.setResultsMetaData(resultsMetaData);
		resultsDto.setCcdaValidationResults(validatorResults);
		return resultsDto;
	}

	private static void processValidateCCDAException(ValidationResultsMetaData resultsMetaData,
			String serviceErrorStart, String validationObjective, Exception exception) {
		resultsMetaData.setServiceError(true);
		String fullErrorWithStackTrace = serviceErrorStart + ExceptionUtils.getStackTrace(exception);
		if (exception.getMessage() != null) {
			String fullError = serviceErrorStart + exception.getMessage();
			resultsMetaData.setServiceErrorMessage(fullError);
		} else {
			resultsMetaData.setServiceErrorMessage(fullErrorWithStackTrace);
		}
		logger.error(fullErrorWithStackTrace);
		resultsMetaData.setObjectiveProvided(validationObjective);
	}

	private List<RefCCDAValidationResult> runValidators(String validationObjective, String referenceFileName,
			MultipartFile ccdaFile, boolean curesUpdate, boolean svap2022, boolean svap2023, boolean uscdiv4,
			String vocabularyConfig, SeverityLevel severityLevel)
			throws SAXException, Exception {
		List<RefCCDAValidationResult> validatorResults = new ArrayList<>();
		InputStream ccdaFileInputStream = null;
		try {
			ccdaFileInputStream = ccdaFile.getInputStream();
			BOMInputStream bomInputStream = new BOMInputStream(ccdaFileInputStream);
			if (bomInputStream.hasBOM()) {
				logger.warn(
						"The C-CDA file has a BOM which is supposed to be removed by BOMInputStream - encoding w/o BOM: "
								+ bomInputStream.getBOMCharsetName());
			}
			String ccdaFileContents = IOUtils.toString(bomInputStream, "UTF-8");

			List<RefCCDAValidationResult> mdhtResults = doMDHTValidation(validationObjective, referenceFileName,
					ccdaFileContents, severityLevel);
			if (mdhtResults != null && !mdhtResults.isEmpty()) {
				logger.info("Adding MDHT results");
				validatorResults.addAll(mdhtResults);
			}

			boolean isSchemaErrorInMdhtResults = mdhtResultsHaveSchemaError(mdhtResults);
			boolean isObjectiveAllowingVocabularyValidation = objectiveAllowsVocabularyValidation(validationObjective);
			if (!isSchemaErrorInMdhtResults && isObjectiveAllowingVocabularyValidation) {
				if (vocabularyConfig == null || vocabularyConfig.isEmpty()) {
					logger.warn("Invalid vocabularyConfig of '" + vocabularyConfig != null ? vocabularyConfig
							: "null" + "' " + "received. Assigned default config of '"
									+ VocabularyConstants.Config.DEFAULT + "'.");
					vocabularyConfig = VocabularyConstants.Config.DEFAULT;
				}
				List<RefCCDAValidationResult> vocabResults = doVocabularyValidation(validationObjective,
						referenceFileName, ccdaFileContents, vocabularyConfig, severityLevel);
				if (vocabResults != null && !vocabResults.isEmpty()) {
					logger.info("Adding Vocabulary results");
					validatorResults.addAll(vocabResults);
				}
				if (objectiveAllowsContentValidation(validationObjective)) {
					List<RefCCDAValidationResult> contentResults = doContentValidation(validationObjective,
							referenceFileName, ccdaFileContents, curesUpdate, svap2022, svap2023, uscdiv4, severityLevel);
					if (contentResults != null && !contentResults.isEmpty()) {
						logger.info("Adding Content results");
						validatorResults.addAll(contentResults);
					}
				} else {
					logger.info("Skipping Content validation due to: " + "validationObjective ("
							+ (validationObjective != null ? validationObjective : "null objective")
							+ ") is not relevant or valid for Content validation");
				}
			} else {
				String separator = !isObjectiveAllowingVocabularyValidation && isSchemaErrorInMdhtResults ? " and "
						: "";
				logger.info("Skipping Vocabulary (and thus Content) validation due to: "
						+ (isObjectiveAllowingVocabularyValidation ? ""
								: "validationObjective POSTed: "
										+ (validationObjective != null ? validationObjective : "null objective")
										+ separator)
						+ (isSchemaErrorInMdhtResults ? "C-CDA Schema error(s) found" : ""));
			}
		} catch (IOException e) {
			throw new RuntimeException("Error getting CCDA contents from provided file", e);
		} finally {
			closeFileInputStream(ccdaFileInputStream);
			ccdaFile = null;
		}
		return validatorResults;
	}

	private boolean mdhtResultsHaveSchemaError(List<RefCCDAValidationResult> mdhtResults) {
		for (RefCCDAValidationResult result : mdhtResults) {
			if (result.isSchemaError()) {
				return true;
			}
		}
		return false;
	}

	private boolean objectiveAllowsVocabularyValidation(String validationObjective) {
		return !validationObjective.equalsIgnoreCase(ValidationObjectives.Sender.C_CDA_IG_ONLY)
				&& !referenceCCDAValidator.isValidationObjectiveMu2Type()
				&& !validationObjective.equalsIgnoreCase(CCDATypes.NON_SPECIFIC_CCDA);
	}

	private boolean objectiveAllowsContentValidation(String validationObjective) {
		return ReferenceCCDAValidator.isValidationObjectiveACertainType(validationObjective,
				ValidationObjectives.ALL_UNIQUE_CONTENT_ONLY);
	}

	private List<RefCCDAValidationResult> doMDHTValidation(String validationObjective, String referenceFileName,
			String ccdaFileContents, SeverityLevel severityLevel) throws SAXException, Exception {
		logger.info("Attempting MDHT validation...");
		return referenceCCDAValidator.validateFile(validationObjective, referenceFileName, ccdaFileContents,
				severityLevel);
	}

	private ArrayList<RefCCDAValidationResult> doVocabularyValidation(String validationObjective,
			String referenceFileName, String ccdaFileContents, String vocabularyConfig, SeverityLevel severityLevel)
			throws SAXException {
		logger.info("Attempting Vocabulary validation...");
		return vocabularyCCDAValidator.validateFile(validationObjective, referenceFileName, ccdaFileContents,
				vocabularyConfig, severityLevel);
	}

	private List<RefCCDAValidationResult> doContentValidation(String validationObjective, String referenceFileName,
			String ccdaFileContents, boolean curesUpdate, boolean svap2022, boolean svap2023, boolean uscdiv4, SeverityLevel severityLevel)
			throws SAXException {
		logger.info("Attempting Content validation...");
		return goldMatchingValidator.validateFile(validationObjective, referenceFileName, ccdaFileContents, 
				curesUpdate, svap2022, svap2023, uscdiv4,
				severityLevel);
	}

	private ValidationResultsMetaData buildValidationMedata(List<RefCCDAValidationResult> validatorResults,
			String validationObjective, SeverityLevel severityLevel) {
		ValidationResultsMetaData resultsMetaData = new ValidationResultsMetaData();
		
		for (RefCCDAValidationResult result : validatorResults) {
			resultsMetaData.addCount(result.getType());
		}
		resultsMetaData.setObjectiveProvided(validationObjective);
		resultsMetaData.setCcdaDocumentType(referenceCCDAValidator.getCcdaDocumentType());
		resultsMetaData.setCcdaVersion(referenceCCDAValidator.getCcdaVersion().getVersion());
		resultsMetaData.setTotalConformanceErrorChecks(referenceCCDAValidator.getTotalConformanceErrorChecks());
		resultsMetaData.setSeverityLevel(severityLevel.name());
		GlobalCodeValidatorResults globalCodeValidatorResults = vocabularyCCDAValidator.getGlobalCodeValidatorResults();
		resultsMetaData.setVocabularyValidationConfigurationsCount(
				globalCodeValidatorResults.getVocabularyValidationConfigurationsCount());
		resultsMetaData.setVocabularyValidationConfigurationsErrorCount(
				globalCodeValidatorResults.getVocabularyValidationConfigurationsErrorCount());
		
		return resultsMetaData;
	}

	private void closeFileInputStream(InputStream fileIs) {
		if (fileIs != null) {
			try {
				fileIs.close();
			} catch (IOException e) {
				throw new RuntimeException("Error closing CCDA file input stream", e);
			}
		}
	}
}

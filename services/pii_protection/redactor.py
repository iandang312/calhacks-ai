from presidio_analyzer import AnalyzerEngine
from presidio_analyzer.nlp_engine import NlpEngineProvider
from presidio_anonymizer import AnonymizerEngine

from .recognizers import (
    BiometricRecognizer,
    MACAddressRecognizer,
    PasswordRecognizer,
    SSNRecognizer,
    ZipCodeRecognizer,
)


class PIIRedactor:
    """
    Wraps Presidio to redact PII from agent input text.

    Detected entities are replaced with <ENTITY_TYPE> tokens so downstream
    LLM calls never receive raw sensitive values.  The token preserves
    *what* was redacted (e.g. <US_SSN>) for audit logs without leaking data.

    Built-in presidio entities covered:
      EMAIL_ADDRESS, PHONE_NUMBER, CREDIT_CARD, US_SSN, US_ZIPCODE,
      IP_ADDRESS, PERSON, LOCATION, DATE_TIME, URL, CRYPTO, IBAN_CODE,
      US_BANK_NUMBER, US_DRIVER_LICENSE, US_ITIN, US_PASSPORT,
      US_NPI, MEDICAL_LICENSE, US_LICENSE_PLATE

    Custom entities added here:
      PASSWORD    — trigger-word + value pattern
      BIOMETRIC   — blood type, weight, height, blood pressure
      MAC_ADDRESS — colon-separated hex octets
    """

    def __init__(self, spacy_model: str = "en_core_web_lg"):
        provider = NlpEngineProvider(nlp_configuration={
            "nlp_engine_name": "spacy",
            "models": [{"lang_code": "en", "model_name": spacy_model}],
        })
        nlp_engine = provider.create_engine()

        self._analyzer = AnalyzerEngine(nlp_engine=nlp_engine)
        for recognizer in (
            PasswordRecognizer(),
            BiometricRecognizer(),
            MACAddressRecognizer(),
            SSNRecognizer(),
            ZipCodeRecognizer(),
        ):
            self._analyzer.registry.add_recognizer(recognizer)

        self._anonymizer = AnonymizerEngine()

    def redact(self, text: str) -> str:
        """Return *text* with every detected PII span replaced by <ENTITY_TYPE>."""
        results = self._analyzer.analyze(text=text, language="en")
        if not results:
            return text
        return self._anonymizer.anonymize(text=text, analyzer_results=results).text

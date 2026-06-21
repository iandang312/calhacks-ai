from presidio_analyzer import Pattern, PatternRecognizer


class PasswordRecognizer(PatternRecognizer):
    """
    Matches trigger word (password/passwd/pwd/secret) followed by the value.
    The entire phrase is tagged PASSWORD so the value cannot be inferred from
    the redacted text.
    """

    PATTERNS = [
        Pattern(
            "password_trigger",
            r"(?i)\b(?:password|passwd|pwd|secret)\b\s+\S+",
            0.85,
        )
    ]
    CONTEXT = ["password", "passwd", "pwd", "secret", "credential", "login"]

    def __init__(self):
        super().__init__(
            supported_entity="PASSWORD",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
        )


class BiometricRecognizer(PatternRecognizer):
    """
    Matches structured biometric values that appear in natural-language text:
    blood type, weight, height (cm), blood pressure.

    These patterns have a low base score and rely on nearby context words
    (blood, weight, height, pressure …) to be boosted above the detection
    threshold, reducing false positives on bare numbers.
    """

    PATTERNS = [
        Pattern("blood_type",      r"\b(?:A|B|AB|O)[+-]",               0.6),
        Pattern("weight",          r"\b\d{2,3}\s*(?:lbs?|kg|pounds?|kilograms?)\b", 0.5),
        Pattern("height_cm",       r"\b\d{2,3}\s*cm\b",                 0.4),
        Pattern("blood_pressure",  r"\b\d{2,3}/\d{2,3}\b",              0.5),
    ]
    CONTEXT = [
        "blood", "type", "weight", "height", "pressure", "bmi",
        "biometric", "fingerprint", "pulse", "heart",
    ]

    def __init__(self):
        super().__init__(
            supported_entity="BIOMETRIC",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
        )


class MACAddressRecognizer(PatternRecognizer):
    """Matches six colon-separated hex octets (e.g. 00:1A:2B:3C:4D:5E)."""

    PATTERNS = [
        Pattern(
            "mac_address",
            r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b",
            0.95,
        )
    ]
    CONTEXT = ["mac", "address", "device", "network", "hardware", "ethernet"]

    def __init__(self):
        super().__init__(
            supported_entity="MAC_ADDRESS",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
        )


class SSNRecognizer(PatternRecognizer):
    """
    US Social Security Number — explicit recognizer with a high base score.

    Presidio's built-in UsSsnRecognizer uses a base score of 0.5 and relies
    on context boosting to clear the threshold.  In short inputs the context
    window is narrow enough that the boost does not always apply.  This
    recognizer sets the base score to 0.85 so it fires unconditionally
    when the SSN format is present.
    """

    PATTERNS = [
        Pattern("ssn_dashes", r"\b\d{3}-\d{2}-\d{4}\b", 0.85),
        Pattern("ssn_spaces", r"\b\d{3} \d{2} \d{4}\b", 0.6),
    ]
    CONTEXT = ["ssn", "social security", "social security number", "ss#"]

    def __init__(self):
        super().__init__(
            supported_entity="US_SSN",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
        )


class ZipCodeRecognizer(PatternRecognizer):
    """
    US ZIP code — explicit recognizer with a score above the detection threshold.

    Presidio's built-in UsZipCodeRecognizer uses a base score of 0.01 for the
    5-digit pattern, requiring context boosting to reach 0.36.  In practice
    the boost does not always apply.  This recognizer sets the base score to
    0.4 so it fires reliably whenever a 5-digit number appears, and 0.85 for
    the ZIP+4 format which is unambiguous.
    """

    PATTERNS = [
        Pattern("zip_5",   r"\b\d{5}\b",       0.4),
        Pattern("zip_9",   r"\b\d{5}-\d{4}\b", 0.85),
    ]
    CONTEXT = ["zip", "zipcode", "zip code", "postal", "postal code"]

    def __init__(self):
        super().__init__(
            supported_entity="US_ZIPCODE",
            patterns=self.PATTERNS,
            context=self.CONTEXT,
        )

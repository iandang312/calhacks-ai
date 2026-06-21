"""
PII redaction tests — one test per category.

Each test defines a concrete input prompt and the exact expected output
after redaction. On failure it prints what was actually produced.

Run with:  pytest tests/services/test_pii_redactor.py -v -s
"""

import pytest
from services.pii_protection import PIIRedactor


@pytest.fixture(scope="module")
def redactor():
    return PIIRedactor()


def _check(label: str, actual: str, expected: str) -> None:
    if actual == expected:
        print(f"\nPASS: {label}")
    else:
        print(f"\nFAIL: {label}")
        print(f"  expected: {expected!r}")
        print(f"  actual:   {actual!r}")
    assert actual == expected, f"[{label}] expected {expected!r}, got {actual!r}"


# ── 1. Passwords ──────────────────────────────────────────────────────────────

def test_password_redaction(redactor):
    """Trigger word + value — full phrase is replaced."""
    input_text = "password Tr0ub4dor&3"
    expected   = "<PASSWORD>"
    _check("password", redactor.redact(input_text), expected)


# ── 2. Personal information ───────────────────────────────────────────────────

def test_personal_info_redaction(redactor):
    """Email address is replaced; surrounding text is preserved."""
    input_text = "contact jane.doe@example.com for support"
    expected   = "contact <EMAIL_ADDRESS> for support"
    _check("personal info / email", redactor.redact(input_text), expected)


# ── 3. Geographic data ────────────────────────────────────────────────────────

def test_geographic_redaction(redactor):
    """US zip code is replaced via regex recognizer."""
    input_text = "zip code 90210"
    expected   = "zip code <US_ZIPCODE>"
    _check("geographic / zip code", redactor.redact(input_text), expected)


# ── 4. Contacts ───────────────────────────────────────────────────────────────

def test_contacts_redaction(redactor):
    """Phone number (E.164 format) is replaced."""
    input_text = "call me at +1-800-555-0100"
    expected   = "call me at <PHONE_NUMBER>"
    _check("contacts / phone", redactor.redact(input_text), expected)


# ── 5. Financial data ─────────────────────────────────────────────────────────

def test_financial_redaction(redactor):
    """Visa test card number (passes Luhn) is replaced."""
    input_text = "card number 4111111111111111"
    expected   = "card number <CREDIT_CARD>"
    _check("financial / credit card", redactor.redact(input_text), expected)


# ── 6. Biometrics ─────────────────────────────────────────────────────────────

def test_biometric_redaction(redactor):
    """Blood type string is replaced by the custom BiometricRecognizer."""
    input_text = "blood type AB+"
    expected   = "blood type <BIOMETRIC>"
    _check("biometrics / blood type", redactor.redact(input_text), expected)


# ── 7. Identity / government / legal documents ────────────────────────────────

def test_identity_redaction(redactor):
    """US Social Security Number is replaced."""
    input_text = "ssn 123-45-6789"
    expected   = "ssn <US_SSN>"
    _check("identity / SSN", redactor.redact(input_text), expected)


# ── 8. Device analytics & network fingerprinting ──────────────────────────────

def test_network_fingerprint_redaction(redactor):
    """IPv4 address and MAC address are both replaced in one pass."""
    input_text = "ip 192.168.1.1 mac 00:1A:2B:3C:4D:5E"
    expected   = "ip <IP_ADDRESS> mac <MAC_ADDRESS>"
    _check("network / IP + MAC", redactor.redact(input_text), expected)

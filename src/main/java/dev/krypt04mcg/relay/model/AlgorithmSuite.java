package dev.krypt04mcg.relay.model;

public record AlgorithmSuite(String kem, String signature, String aead, String hkdf) {
}

package it.polimi.utility;

import java.security.SecureRandom;

/**
 * Chat that creates all random IDs used in the project.
 */
public class Randomize {
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static String generateRandomString(int length) {
        return generateRandomString(length, CHARACTERS);
    }

    public static int generateRandomPort() {
        // Port range is 1-65535
        return generateRandomNumberInRange();
    }

    private static String generateRandomString(int length, String characters) {
        if (length <= 0 || characters == null || characters.isEmpty()) {
            throw new IllegalArgumentException("Invalid input parameters");
        }

        StringBuilder randomString = new StringBuilder(length);
        SecureRandom random = new SecureRandom();

        for (int i = 0; i < length; i++) {
            int randomIndex = random.nextInt(characters.length());
            char randomChar = characters.charAt(randomIndex);
            randomString.append(randomChar);
        }

        return randomString.toString();
    }

    private static int generateRandomNumberInRange() {
        SecureRandom random = new SecureRandom();
        return random.nextInt(65535 - 12345 + 1) + 12345;
    }

}

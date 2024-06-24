package it.polimi.utility;

import java.security.SecureRandom;

/**
 * Chat that creates all random IDs used in the project.
 */
public class Randomize {
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static String generateRandomString(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Invalid input parameters");
        }

        StringBuilder randomString = new StringBuilder(length);
        SecureRandom random = new SecureRandom();

        for (int i = 0; i < length; i++) {
            int randomIndex = random.nextInt(CHARACTERS.length());
            char randomChar = CHARACTERS.charAt(randomIndex);
            randomString.append(randomChar);
        }

        return randomString.toString();
    }

    public static int generateRandomPort() {
        // Port range is 12345-65535
        SecureRandom random = new SecureRandom();
        return random.nextInt(65535 - 12345 + 1) + 12345;
    }

}

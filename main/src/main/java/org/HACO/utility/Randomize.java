package org.HACO.utility;

import java.security.SecureRandom;

public class Randomize {
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String NUMBERS = "0123456789";

    public static String generateRandomString(int length) {
        return generateRandomString(length, CHARACTERS);
    }

    public static String generateRandomNumberString(int length) {
        return generateRandomString(length, NUMBERS);
    }

    public static int generateRandomUDPPort() {
        // Port range is 1-65535
        return generateRandomNumberInRange(12345, 65535);
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

    private static int generateRandomNumberInRange(int min, int max) {
        SecureRandom random = new SecureRandom();
        return random.nextInt(max - min + 1) + min;
    }

}

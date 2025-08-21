/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.commons.auth.user;

import org.apache.iotdb.commons.auth.AuthException;
import org.apache.iotdb.commons.auth.entity.User;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.utils.AuthUtils;
import org.apache.iotdb.rpc.TSStatusCode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/** User password modification tool */
public class UserPasswordModifier {

  private final LocalFileUserManager userManager;

  public UserPasswordModifier(String userDirPath) throws AuthException {
    this.userManager = new LocalFileUserManager(userDirPath);
    this.userManager.reset();
    CommonDescriptor.getInstance().getConfig().setEnforceStrongPassword(true);
  }

  /** Modify user password */
  public boolean modifyPassword(
      String username, String oldPassword, String newPassword, boolean needCheckOld)
      throws AuthException, IOException {
    // 1. Check if the user exists
    if (userManager.getEntity(username) == null) {
      return false; // Return false directly, let the caller decide how to handle
    }

    // 2. If old password check is required
    if (needCheckOld) {
      String storedPassword = userManager.getEntity(username).getPassword();
      String encryptedOldPassword = AuthUtils.encryptPassword(oldPassword);
      if (!storedPassword.equals(encryptedOldPassword)) {
        throw new AuthException(TSStatusCode.WRONG_LOGIN_PASSWORD, "Old password is incorrect");
      }
    }

    // 3. Validate new password according to rules
    if (CommonDescriptor.getInstance().getConfig().isEnforceStrongPassword()) {
      if (username.equals(newPassword)) {
        throw new AuthException(
            TSStatusCode.ILLEGAL_PASSWORD, "Password cannot be the same as username");
      }
      AuthUtils.validatePassword(newPassword);
    }

    // 4. Update the password
    boolean success = userManager.updateUserPassword(username, newPassword, false);
    if (success) {
      User user = userManager.getEntity(username);
      userManager.getAccessor().saveEntity(user);
    }
    return success;
  }

  /** Recursively find all system/users directories under the given baseDir */
  private static List<File> findAllUserDirs(File baseDir) {
    List<File> result = new ArrayList<>();
    findAllUserDirsRecursive(baseDir, result);
    return result;
  }

  private static void findAllUserDirsRecursive(File dir, List<File> result) {
    if (dir == null || !dir.exists() || !dir.isDirectory()) {
      return;
    }
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File f : files) {
      if (f.isDirectory()) {
        if (f.getPath().replace("\\", "/").endsWith("system/users")) {
          result.add(f);
        }
        findAllUserDirsRecursive(f, result);
      }
    }
  }

  /** Interactive password modification tool */
  public static void interactiveModify() {
    try (Scanner scanner = new Scanner(System.in)) {
      // 1. Get user directory path (input IoTDB data root path)
      System.out.print("Enter IoTDB data base path: ");
      String dataRoot = scanner.nextLine();
      List<File> userDirs = findAllUserDirs(new File(dataRoot));
      if (userDirs.isEmpty()) {
        System.out.println("Error: Could not find any system/users directory under " + dataRoot);
        return;
      }

      // 2. Get username and check if it exists in at least one directory
      String username;
      boolean exists = false;
      while (true) {
        System.out.print("Enter username: ");
        username = scanner.nextLine();
        for (File userDir : userDirs) {
          try {
            UserPasswordModifier modifier = new UserPasswordModifier(userDir.getAbsolutePath());
            if (modifier.userManager.getEntity(username) != null) {
              exists = true;
              break;
            }
          } catch (AuthException ignore) {
          }
        }
        if (!exists) {
          System.out.println(
              "Error: User " + username + " does not exist in any directory, please try again.");
        } else {
          break;
        }
      }

      // 3. Input old password and verify correctness
      String oldPassword;
      int retryCount = 0;
      while (true) {
        System.out.print("Enter old password: ");
        oldPassword = scanner.nextLine();

        boolean valid = false;
        for (File userDir : userDirs) {
          try {
            UserPasswordModifier modifier = new UserPasswordModifier(userDir.getAbsolutePath());
            if (modifier.userManager.getEntity(username) != null) {
              String storedPassword = modifier.userManager.getEntity(username).getPassword();
              String encryptedOldPassword = AuthUtils.encryptPassword(oldPassword);
              if (storedPassword.equals(encryptedOldPassword)) {
                valid = true;
                break;
              }
            }
          } catch (Exception ignore) {
          }
        }

        if (valid) {
          break; // Old password is correct
        } else {
          System.out.println("Error: Old password is incorrect, please try again.");
          retryCount++;
          if (retryCount >= 3) { // Allow maximum 3 retries
            System.out.println("Too many failed attempts. Exiting...");
            return;
          }
        }
      }

      // 4. Input new password
      System.out.print("Enter new password: ");
      String newPassword = scanner.nextLine();

      System.out.print("Confirm new password: ");
      String confirmPassword = scanner.nextLine();

      if (!newPassword.equals(confirmPassword)) {
        System.out.println("Error: New passwords do not match");
        return;
      }

      // 5. Update password in all system/users directories where user exists
      boolean allSuccess = true;
      for (File userDir : userDirs) {
        try {
          UserPasswordModifier modifier = new UserPasswordModifier(userDir.getAbsolutePath());
          if (modifier.userManager.getEntity(username) == null) {
            System.out.println(userDir.getAbsolutePath() + ": Skipped (user not found)");
            continue;
          }
          boolean success = modifier.modifyPassword(username, oldPassword, newPassword, true);
          System.out.println(
              userDir.getAbsolutePath()
                  + ": "
                  + (success ? "Password updated successfully" : "Failed to update password"));
          if (!success) {
            allSuccess = false;
          }
        } catch (Exception e) {
          System.out.println(userDir.getAbsolutePath() + ": Error - " + e.getMessage());
          allSuccess = false;
        }
      }

      if (allSuccess) {
        System.out.println("Password updated successfully in all user directories.");
      } else {
        System.out.println("Some user directories failed to update password.");
      }
    }
  }

  public static void main(String[] args) {
    if (args.length == 0
        || (args.length == 1 && ("-i".equals(args[0]) || "--interactive".equals(args[0])))) {
      // Interactive mode
      interactiveModify();
      return;
    }

    if (args.length == 1 && ("-h".equals(args[0]) || "--help".equals(args[0]))) {
      printHelp();
      return;
    }

    if (args.length == 4) {
      // Command line mode: <dataRoot> <username> <oldPassword> <newPassword>
      List<File> userDirs = findAllUserDirs(new File(args[0]));
      if (userDirs.isEmpty()) {
        System.out.println("Error: Could not find any system/users directory under " + args[0]);
        return;
      }

      String username = args[1];
      String oldPassword = args[2];
      String newPassword = args[3];

      // Check if the user exists in at least one directory
      boolean exists = false;
      for (File userDir : userDirs) {
        try {
          UserPasswordModifier modifier = new UserPasswordModifier(userDir.getAbsolutePath());
          if (modifier.userManager.getEntity(username) != null) {
            exists = true;
            break;
          }
        } catch (AuthException ignore) {
        }
      }
      if (!exists) {
        System.out.println("Error: User " + username + " does not exist in any directory");
        return;
      }

      boolean allSuccess = true;
      for (File userDir : userDirs) {
        try {
          UserPasswordModifier modifier = new UserPasswordModifier(userDir.getAbsolutePath());
          if (modifier.userManager.getEntity(username) == null) {
            System.out.println(userDir.getAbsolutePath() + ": Skipped (user not found)");
            continue;
          }
          boolean success = modifier.modifyPassword(username, oldPassword, newPassword, true);
          System.out.println(
              userDir.getAbsolutePath()
                  + ": "
                  + (success ? "Password updated successfully" : "Failed to update password"));
          if (!success) {
            allSuccess = false;
          }
        } catch (Exception e) {
          System.out.println(userDir.getAbsolutePath() + ": Error - " + e.getMessage());
          allSuccess = false;
        }
      }

      if (allSuccess) {
        System.out.println("Password updated successfully in all user directories.");
      } else {
        System.out.println("Some user directories failed to update password.");
      }
      return;
    }

    // Invalid usage
    System.out.println("Error: Invalid arguments.");
    printHelp();
  }

  private static void printHelp() {
    System.out.println(
        "Usage: java -cp ... org.apache.iotdb.commons.auth.user.UserPasswordModifier [options]");
    System.out.println("Options:");
    System.out.println("  -h, --help                           Show this help message");
    System.out.println("  -i, --interactive                    Run in interactive mode");
    System.out.println("  <dataRoot> <username> <oldPassword> <newPassword>");
    System.out.println(
        "                                       Change password with old password verification");
  }
}

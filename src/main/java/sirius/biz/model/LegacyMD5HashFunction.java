/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.model;

import com.google.common.hash.Hashing;
import sirius.kernel.di.std.Register;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Provides an implementation which uses MD5 as hash function to protect the password.
 * <p>
 * As MD5 can be computed quite fast, it is considered too weak to be used to hash passwords.
 * Therefore we permit logins using these hashes but re-hash them using a stronger function.
 */
@Register
public class LegacyMD5HashFunction implements PasswordHashFunction {

    @Override
    public String computeHash(@Nullable String username, @Nullable String salt, @Nonnull String password) {
        String hashInput = salt != null ? salt + password : password;
        return Base64.getEncoder()
                     .encodeToString(Hashing.md5().hashString(hashInput, StandardCharsets.UTF_8).asBytes());
    }

    @Override
    public boolean isOutdated() {
        return true;
    }

    @Override
    public int getPriority() {
        return 1000;
    }
}

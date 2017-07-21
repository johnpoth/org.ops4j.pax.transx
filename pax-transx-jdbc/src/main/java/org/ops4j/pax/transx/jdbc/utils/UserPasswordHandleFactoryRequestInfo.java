/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.ops4j.pax.transx.jdbc.utils;

import org.ops4j.pax.transx.connection.utils.UserPasswordConnectionRequestInfo;

import javax.resource.spi.ConnectionRequestInfo;
import java.util.Objects;

public abstract class UserPasswordHandleFactoryRequestInfo<CI extends AbstractConnectionHandle<?, ?, CI>>
        implements UserPasswordConnectionRequestInfo {

    private final String userName;
    private final String password;
    private final transient int hashcode;

    public UserPasswordHandleFactoryRequestInfo(String userName,
                                                String password) {
        this.userName = userName;
        this.password = password;
        this.hashcode = Objects.hash(userName, password);
    }

    public abstract CI createConnectionHandle(ConnectionRequestInfo cri);

    public String getUserName() {
        return userName;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserPasswordHandleFactoryRequestInfo)) return false;
        UserPasswordHandleFactoryRequestInfo<?> that = (UserPasswordHandleFactoryRequestInfo<?>) o;
        return hashcode == that.hashcode
                && Objects.equals(userName, that.userName)
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    @Override
    public String toString() {
        return "UserPasswordHandleFactoryRequestInfo[" + userName + "]";
    }
}

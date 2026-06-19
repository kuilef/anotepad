package android.net;

import android.os.Parcel;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TestUri extends Uri {
    private final String value;

    public TestUri(String value) {
        this.value = value;
    }

    @Override
    public boolean isHierarchical() {
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String getScheme() {
        return "content";
    }

    @Override
    public String getSchemeSpecificPart() {
        return value;
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return value;
    }

    @Override
    public String getAuthority() {
        return "test";
    }

    @Override
    public String getEncodedAuthority() {
        return getAuthority();
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return "test";
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return value;
    }

    @Override
    public String getEncodedPath() {
        return value;
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.singletonList(value);
    }

    @Override
    public String getLastPathSegment() {
        return value;
    }

    @Override
    public Builder buildUpon() {
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TestUri && value.equals(((TestUri) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public int compareTo(Uri other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        return "content://test/" + value;
    }
}

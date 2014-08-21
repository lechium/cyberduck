package ch.cyberduck.core.features;

/*
 * Copyright (c) 2002-2014 David Kocher. All rights reserved.
 * http://cyberduck.io/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * feedback@cyberduck.io
 */

import ch.cyberduck.core.Path;
import ch.cyberduck.core.exception.BackgroundException;

import java.util.Set;

/**
 * @version $Id$
 */
public interface Location {

    Set<Name> getLocations();

    Name getLocation(Path container) throws BackgroundException;

    public static abstract class Name {
        private String identifier;

        protected Name(String identifier) {
            this.identifier = identifier;
        }

        public String getIdentifier() {
            return identifier;
        }

        public abstract String toString();

        @Override
        public boolean equals(Object o) {
            if(this == o) {
                return true;
            }
            if(o == null || getClass() != o.getClass()) {
                return false;
            }
            Name name = (Name) o;
            if(identifier != null ? !identifier.equals(name.identifier) : name.identifier != null) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return identifier != null ? identifier.hashCode() : 0;
        }
    }
}

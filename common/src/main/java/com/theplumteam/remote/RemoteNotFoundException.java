package com.theplumteam.remote;

/** The CDN, or the manifest, does not have the requested file; for a code it means the code is unknown. */
public class RemoteNotFoundException extends RemoteException {
    public RemoteNotFoundException(String message) {
        super(message);
    }
}

package com.chanceit;

public enum RegistrationWaitState {
    WAIT_HELLO,   // PlayerRegistration is blocked on its socket waiting for a HELLO: registration message
    WAIT_GOODBYE, // PlayerRegistration is blocked on its socket waiting for an unregister message
    WAIT_QUEUE,   // PlayerRegistration is waiting/synchronized on the regstrationQueue AFTER receiving a Goodbye message;
                  // it is waiting to access the queue to remove itself.  If it isn't in the queue it can't unregister.
    WAIT_TURN     // after receiving HELLO the socket blocks on IO for 1 sec for GOODBYE.  If the registraion is part of a game,
                  // after a IO timeout the registration state will change to WAIT_TURN
}

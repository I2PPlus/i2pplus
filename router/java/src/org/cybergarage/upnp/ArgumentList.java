/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp;

import java.util.Vector;

/**
 * A collection of UPnP action arguments.
 *
 * <p>This class extends Vector to manage multiple Argument objects that represent parameters for
 * UPnP service actions. It provides type-safe collection management for action argument
 * definitions.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe argument collection
 *   <li>XML element name constant
 *   <li>Vector-based implementation for efficiency
 *   <li>Service description integration
 *   <li>Action parameter management
 * </ul>
 *
 * <p>This class is used by UPnP services to manage collections of action arguments, enabling proper
 * XML description generation and action invocation parameter handling.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ArgumentList extends Vector<Argument> {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    public static final String ELEM_NAME = "argumentList";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public ArgumentList() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public Argument getArgument(int n) {
        return get(n);
    }

    public Argument getArgument(String name) {
        int nArgs = size();
        for (int n = 0; n < nArgs; n++) {
            Argument arg = getArgument(n);
            String argName = arg.getName();
            if (argName == null) continue;
            if (argName.equals(name) == true) return arg;
        }
        return null;
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////
    /**
     * @deprecated
     */
    @Deprecated
    public void set(ArgumentList inArgList) {
        int nInArgs = inArgList.size();
        for (int n = 0; n < nInArgs; n++) {
            Argument inArg = inArgList.getArgument(n);
            String inArgName = inArg.getName();
            Argument arg = getArgument(inArgName);
            if (arg == null) continue;
            arg.setValue(inArg.getValue());
        }
    }

    /**
     * Set all the Argument which are Input Argoument to the given value in the argument list
     *
     * @param inArgList
     */
    public void setReqArgs(ArgumentList inArgList) {
        int nArgs = size();
        for (int n = 0; n < nArgs; n++) {
            Argument arg = getArgument(n);
            if (arg.isInDirection()) {
                String argName = arg.getName();
                Argument inArg = inArgList.getArgument(argName);
                if (inArg == null)
                    throw new IllegalArgumentException("Argument \"" + argName + "\" missing.");
                arg.setValue(inArg.getValue());
            }
        }
    }

    /**
     * Set all the Argument which are Output Argoument to the given value in the argument list
     *
     * @param outArgList
     */
    public void setResArgs(ArgumentList outArgList) {
        int nArgs = size();
        for (int n = 0; n < nArgs; n++) {
            Argument arg = getArgument(n);
            if (arg.isOutDirection()) {
                String argName = arg.getName();
                Argument outArg = outArgList.getArgument(argName);
                if (outArg == null)
                    throw new IllegalArgumentException("Argument \"" + argName + "\" missing.");
                arg.setValue(outArg.getValue());
            }
        }
    }
}

/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020 Payara Services Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.glassfish.pfl.tf.spi;

import org.glassfish.pfl.tf.spi.annotation.TFEnhanced;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class EnhancedClassDataASMImpl extends EnhancedClassDataBase {
    private final ClassNode currentClass ;
    private static final String TFENHANCED_ANNO_NAME =
        Type.getInternalName( TFEnhanced.class ) ;

    // Get Set<String> for MM annotations present on class
    private void processClassAnnotations() {
        final List<AnnotationNode> classAnnotations =
            currentClass.visibleAnnotations ;
        if (classAnnotations != null) {
            for (AnnotationNode an : classAnnotations) {
                final String aname = Type.getType( an.desc ).getInternalName() ;
                if (annotationNames.contains( aname )) {
                    annoNamesForClass.add( aname ) ;
		}
	    }

	    List<String> acnames = new ArrayList<String>( annoNamesForClass ) ;
	    Collections.sort( acnames ) ;

	    int ctr=0 ;
	    for (String aname : acnames ) {
	        annoToHolderName.put( aname, "__$mm$__" + ctr ) ;
		ctr++ ;
            }

            if (util.getDebug()) {
                util.msg( "Enhancing class " + currentClass.name ) ;
                util.msg( "\tannoNamesForClass = " + annoNamesForClass ) ;
                util.msg( "\tannoToHolderName = " + annoToHolderName ) ;
            }
        }
    }

    private Object getAttribute( AnnotationNode an, String name ) {
        if (an.values != null) {
            Iterator iter = an.values.iterator() ;
            while (iter.hasNext()) {
                Object key = iter.next() ;
                Object value = iter.next() ;
                if (!(key instanceof String)) {
                    return null ;
                }

                if (key.equals(name)) {
                    return value ;
                }
            }
        }

        return null ;
    }

    // Scan methods:
    //    - Build List<String> to map names of MM annotated methods to ints
    //      validate: such methods must have exactly 1 MM annotation that
    //          is in annoNamesForClass.
    //    - Build Set<String> of all InfoMethod annotated methods.
    //      validate: such methods must be private, return void, and have
    //          an empty body.  May NOT have MM annotation.
    private void scanMethods() {
        final List<MethodNode> methods = currentClass.methods ;
        Map<String,String> mmnToDescriptions =
            new HashMap<String,String>() ;

        Map<String,TimingPointType> mmnToTPT =
            new HashMap<String,TimingPointType>() ;

        Map<String,String> mmnToTPN =
            new HashMap<String,String>() ;

        Map<String,String> mmnToAnnotationName = 
            new HashMap<String,String>() ;

        for (MethodNode mn : methods) {
            final String mname = mn.name ;
            final String mdesc = util.getFullMethodDescriptor( mn ) ;

            String monitoredMethodMMAnno = null ;
            String shortClassName = className ;
            int index = shortClassName.lastIndexOf( '/' ) ;
            if (index >= 0) {
                shortClassName = className.substring( index + 1 ) ;
            }

            String description = "Timer for method " + mname
                + " in class " + shortClassName ; // default
            TimingPointType tpt = TimingPointType.BOTH ; // default for non-InfoMethod
            String tpName = mname ; // default for non-InfoMethod

            boolean hasMethodInfoAnno = false ;

            final List<AnnotationNode> annotations = mn.visibleAnnotations ;
            if (annotations != null) {
                for (AnnotationNode an : annotations) {
                    final String aname =
                        Type.getType( an.desc ).getInternalName() ;

                    if (aname.equals( DESCRIPTION_NAME )) {
                        Object value = getAttribute( an, "value" ) ;
                        if (value != null && value instanceof String) {
                            description = (String)value ;
                        }
                    } else if (aname.equals( INFO_METHOD_NAME)) {
                        // set the correct default for InfoMethod!
                        tpt = TimingPointType.NONE ;

                        // Check for private method!
                        if (!util.hasAccess( mn.access, 
                            Opcodes.ACC_PRIVATE )) {

                            util.error( "Method " + mdesc
                                + " for Class " + currentClass.name
                                + " is a non-private @InfoMethod,"
                                + " which is not allowed" ) ;
                        }

                        hasMethodInfoAnno = true ;

                        Object value = getAttribute( an, "tpType" ) ;
                        if (value != null && value instanceof String[]) {
                            String[] enumData = (String[])value ;
                            if (enumData.length == 2) {
                                // [0] is desc, [1] is value
                                tpt = TimingPointType.valueOf( enumData[1] ) ;
                            }
                        }

                        Object value2 = getAttribute( an, "tpName") ;
                        String tpn = "" ;
                        if ((value2 != null ) && value2 instanceof String) {
                            tpn = (String)value2 ;
                        }

                        if (tpt != TimingPointType.NONE) {
                            if (tpn.length() == 0) {
                                util.error( "Method " + mdesc
                                    + " for Class " + currentClass.name
                                    + " is an info method with timing point type "
                                    + tpt + " but no tpName was specified" )  ;
                            } else {
                                tpName = tpn ;
                            }
                        }
                    } else if (annoNamesForClass.contains( aname)) {
                        if (monitoredMethodMMAnno == null) {
                            monitoredMethodMMAnno = aname ;
                        } else {
                            util.error( "Method " + mdesc
                                + " for Class " + currentClass.name
                                + "has multiple MM annotations" ) ;
                        }
                    } else if (annotationNames.contains( aname )) {
                            util.error( "Method " + mdesc
                                + " for Class " + currentClass.name
                                + " has an MM annotation which "
                                + "is not on its class" ) ;
                    }
                }

                if (hasMethodInfoAnno && monitoredMethodMMAnno != null) {
                    util.error( "Method " + mdesc
                        + " for Class " + currentClass.name
                        + " has both @InfoMethod annotation and"
                        + " a MM annotation" ) ;
                }

                // This check is not really essential, but it simplifies
                // passing information to later phases for code generation
                // if we can assume that all @InfoMethod annotated methods
                // are non-static. (Simply because we only need to look for
                // INVOKESPECIAL).
                final boolean isStatic = util.hasAccess( mn.access,
                    Opcodes.ACC_STATIC ) ;
                if (hasMethodInfoAnno && isStatic) {
                    util.error( "Method " + mdesc            
                        + " for Class " + currentClass.name
                        + " is a static method, but must not be" ) ;
                }

                // TF Annotations are not permitted on constructors
                if (mname.equals( "<init>" )) {
                    if (hasMethodInfoAnno) {
                        util.error( "Constructors must not have an "
                            + "@InfoMethod annotations") ;
                    } else if (monitoredMethodMMAnno != null) {
                        util.error( "Constructors must not have an "
                            + "MM annotation") ;
                    }
                }

                // This will be a null value for InfoMethods, which is what
                // we want.
                mmnToAnnotationName.put(mname, monitoredMethodMMAnno ) ;

                // We could have a method at this point that is annotated with
                // something OTHER than tracing annotations.  Do not add
                // such methods to the ECD.
                if (hasMethodInfoAnno || (monitoredMethodMMAnno != null)) {
                    // Both infoMethods and MM annotated methods go into 
                    // methodNames
                    methodNames.add( mname ) ;

                    mmnToDescriptions.put( mname, description ) ;
                    mmnToTPT.put( mname, tpt ) ;
                    mmnToTPN.put( mname, tpName ) ;

                    if (hasMethodInfoAnno) {
                        infoMethodDescs.add( mdesc ) ;
                    } else {
                        mmMethodDescs.add( mdesc ) ;
                        methodToAnno.put( mdesc, monitoredMethodMMAnno ) ;
                    }
                }
            }
        }

        Collections.sort( methodNames ) ;

        for (String str : methodNames ) {
            methodDescriptions.add( mmnToDescriptions.get( str ) ) ;
            methodTPTs.add( mmnToTPT.get( str ) ) ;
            methodAnnoList.add( mmnToAnnotationName.get( str ) ) ;
            methodTPNames.add( mmnToTPN.get(  str ) ) ;
        }

        if (util.getDebug()) {
            util.msg( "\tinfoMethodDescs = " + infoMethodDescs ) ;
            util.msg( "\tmmMethodDescs = " + mmMethodDescs ) ;
            util.msg( "\tmethodNames = " + methodNames ) ;
            util.msg( "\tmethodToAnno = " + methodToAnno ) ;
            util.msg( "\tmethodDescriptions = " + methodDescriptions ) ;
            util.msg( "\tmethodTPTs = " + methodTPTs ) ;
            util.msg( "\tmethodTPNs = " + methodTPNames ) ;
        }
    }

    public EnhancedClassDataASMImpl( Util util, Set<String> mmAnnotations,
        ClassNode cn ) {

        super( util, mmAnnotations ) ;

        currentClass = cn ;

        // Compute data here: only look at data available to
        // java reflection, so that a runtime version of 
        // EnhancedClassData using reflection can be created.
        className = cn.name ;
        processClassAnnotations() ;
        scanMethods();
    }
}

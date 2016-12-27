/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2015-2016, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.fibers.instrument;

import co.paralleluniverse.fibers.Instrumented;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

import static co.paralleluniverse.fibers.instrument.Classes.*;
import static co.paralleluniverse.fibers.instrument.QuasarInstrumentor.ASMAPI;

/**
 * @author circlespainter
 */
class SuspOffsetsAfterInstrClassVisitor extends ClassVisitor {
    private final MethodDatabase db;
    private String sourceName, className;

    SuspOffsetsAfterInstrClassVisitor(ClassVisitor cv, MethodDatabase db) {
        super(ASMAPI, cv);
        this.db = db;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;

        // need atleast 1.5 for annotations to work
        if (version < Opcodes.V1_5)
            version = Opcodes.V1_5;

        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitSource(String source, String debug) {
        this.sourceName = source;

        super.visitSource(source, debug);
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
        if ((access & Opcodes.ACC_NATIVE) == 0 && !isYieldMethod(className, name)) {
            // Bytecode-level AST of a method, being a MethodVisitor itself can be filled through delegation from another visitor
            final MethodNode mn = new MethodNode(access, name, desc, signature, exceptions);

            // Analyze, fill and enqueue method ASTs
            final MethodVisitor outMV = super.visitMethod(access, name, desc, signature, exceptions);

            return new MethodVisitor(ASMAPI, outMV) {
                private Label currLabel = null;
                private int prevOffset = -1;
                private boolean instrumented;
                private boolean optimized = false;
                private int methodStart = -1, methodEnd = -1;

                private List<Integer> suspOffsetsAfterInstrL = new ArrayList<>();
                private int[] suspCallSites = new int[0];

                @Override
                public AnnotationVisitor visitAnnotation(final String adesc, boolean visible) {
                    if (Classes.INSTRUMENTED_DESC.equals(adesc)) {
                        instrumented = true;

                        return new AnnotationVisitor(ASMAPI) { // Only collect info
                            @Override
                            public void visit(String name, Object value) {
                                if (Instrumented.FIELD_NAME_METHOD_START.equals(name))
                                    methodStart = (Integer) value;
                                else if (Instrumented.FIELD_NAME_METHOD_END.equals(name))
                                    methodEnd = (Integer) value;
                                else if (Instrumented.FIELD_NAME_METHOD_OPTIMIZED.equals(name))
                                    optimized = (Boolean) value;
                                else if (Instrumented.FIELD_NAME_SUSPENDABLE_CALL_SITES.equals(name))
                                    suspCallSites = (int[]) value;
                                else //noinspection StatementWithEmptyBody
                                    if (Instrumented.FIELD_NAME_SUSPENDABLE_CALL_SITES_OFFSETS_AFTER_INSTR.equals(name))
                                    ; // Ignore, we're filling it
                                else
                                    throw new RuntimeException("Unexpected `@Instrumented` field: " + name);
                            }
                        };
                    }

                    return super.visitAnnotation(adesc, visible);
                }

                @Override
                public void visitLocalVariable(String name, String desc, String sig, Label lStart, Label lEnd, int slot) {
                    super.visitLocalVariable(name, desc, sig, lStart, lEnd, slot);
                }

                @Override
                public void visitLabel(Label label) {
                    if (instrumented) {
                        currLabel = label;
                    }

                    super.visitLabel(label);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                    if (instrumented) {
                        final int type = AbstractInsnNode.METHOD_INSN;
                        if (InstrumentMethod.isSuspendableCall(db, type, opcode, owner, name, desc) &&
                                !Classes.STACK_NAME.equals(owner) && // postRestore
                                currLabel != null && currLabel.info instanceof Integer)
                            addLine();
                    }

                    super.visitMethodInsn(opcode, owner, name, desc, isInterface);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String desc, Handle handle, Object... objects) {
                    if (instrumented) {
                        final int type = AbstractInsnNode.INVOKE_DYNAMIC_INSN;
                        final int opcode = Opcodes.INVOKEDYNAMIC;
                        if (InstrumentMethod.isSuspendableCall(db, type, opcode, handle.getOwner(), name, desc) &&
                                !Classes.STACK_NAME.equals(handle.getOwner()) && // postRestore
                                currLabel != null && currLabel.info instanceof Integer)
                            addLine();
                    }
                    super.visitInvokeDynamicInsn(name, desc, handle, objects);
                }

                @Override
                public void visitEnd() {
                    if (instrumented)
                        InstrumentMethod.emitInstrumentedAnn (
                            db, outMV, mn, sourceName, className, optimized, methodStart, methodEnd,
                            suspCallSites, toIntArray(suspOffsetsAfterInstrL)
                        );

                    super.visitEnd();
                }

                private void addLine() {
                    final int currOffset = (Integer) currLabel.info;
                    if (currOffset > prevOffset) {
                        suspOffsetsAfterInstrL.add(currOffset);
                        prevOffset = currOffset;
                    }
                }
            };
        }

        return super.visitMethod(access, name, desc, signature, exceptions);
    }
}

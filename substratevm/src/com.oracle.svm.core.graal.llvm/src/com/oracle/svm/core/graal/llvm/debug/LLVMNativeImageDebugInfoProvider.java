package com.oracle.svm.core.graal.llvm.debug;

import com.oracle.graal.pointsto.infrastructure.OriginalClassProvider;
import com.oracle.graal.pointsto.infrastructure.WrappedJavaMethod;
import com.oracle.objectfile.debuginfo.DebugInfoProvider;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.hosted.annotation.CustomSubstitutionMethod;
import com.oracle.svm.hosted.image.NativeImage;
import com.oracle.svm.hosted.image.sources.SourceManager;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.substitute.SubstitutionMethod;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.nativeimage.ImageSingletons;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.oracle.svm.hosted.image.NativeImageDebugInfoProvider.getDeclaringClass;
import static com.oracle.svm.hosted.image.NativeImageDebugInfoProvider.toJavaName;

/**
 * Incomplete and currently not really used implementation of NativeImageDebugInfoProvider. Will be probably used more later.
 * Implemented by Noisyfox
 * */
public class LLVMNativeImageDebugInfoProvider {
    private final DebugContext debugContext;

    public LLVMNativeImageDebugInfoProvider(DebugContext debugContext) {
        this.debugContext = debugContext;
    }

    public DebugInfoProvider.DebugLineInfo getLineInfo(NodeSourcePosition position) {
        if (position == null) {
            return null;
        }

        return new LLVMNativeImageDebugLineInfo(position);
    }

    class LLVMNativeImageDebugLineInfo implements DebugInfoProvider.DebugLineInfo {

        private final int bci;
        private final ResolvedJavaMethod method;
        private final Path cachePath;
        private Path fullFilePath;

        public LLVMNativeImageDebugLineInfo(NodeSourcePosition position) {
            int posbci = position.getBCI();
            this.bci = (posbci >= 0 ? posbci : 0);
            method = position.getMethod();
            this.cachePath = SubstrateOptions.getDebugInfoSourceCacheRoot();
            computeFullFilePath();
        }

        @Override
        public String fileName() {
            if (fullFilePath != null) {
                Path fileName = fullFilePath.getFileName();
                if (fileName != null) {
                    return fileName.toString();
                }
            }
            return null;
        }

        @Override
        public Path filePath() {
            if (fullFilePath != null) {
                return fullFilePath.getParent();
            }
            return null;
        }

        @Override
        public Path cachePath() {
            return cachePath;
        }

        @Override
        public ResolvedJavaType ownerType() {
            if (method instanceof HostedMethod) {
                return getDeclaringClass((HostedMethod) method, true);
            }
            return method.getDeclaringClass();
        }

        @Override
        public String name() {
            ResolvedJavaMethod targetMethod = method;
            while (targetMethod instanceof WrappedJavaMethod) {
                targetMethod = ((WrappedJavaMethod) targetMethod).getWrapped();
            }
            if (targetMethod instanceof SubstitutionMethod) {
                targetMethod = ((SubstitutionMethod) targetMethod).getOriginal();
            } else if (targetMethod instanceof CustomSubstitutionMethod) {
                targetMethod = ((CustomSubstitutionMethod) targetMethod).getOriginal();
            }
            String name = targetMethod.getName();
            if (name.equals("<init>")) {
                if (method instanceof HostedMethod) {
                    name = getDeclaringClass((HostedMethod) method, true).toJavaName();
                    if (name.indexOf('.') >= 0) {
                        name = name.substring(name.lastIndexOf('.') + 1);
                    }
                    if (name.indexOf('$') >= 0) {
                        name = name.substring(name.lastIndexOf('$') + 1);
                    }
                } else {
                    name = targetMethod.format("%h");
                    if (name.indexOf('$') >= 0) {
                        name = name.substring(name.lastIndexOf('$') + 1);
                    }
                }
            }
            return name;
        }

        @Override
        public String valueType() {
            return method.format("%R");
        }

        @Override
        public int modifiers() {
            return method.getModifiers();
        }

        @Override
        public List<String> paramTypes() {
            Signature signature = method.getSignature();
            int parameterCount = signature.getParameterCount(false);
            List<String> paramTypes = new ArrayList<>(parameterCount);
            for (int i = 0; i < parameterCount; i++) {
                JavaType parameterType = signature.getParameterType(i, null);
                paramTypes.add(toJavaName(parameterType));
            }
            return paramTypes;
        }

        @Override
        public List<String> paramNames() {
            /* Can only provide blank names for now. */
            Signature signature = method.getSignature();
            int parameterCount = signature.getParameterCount(false);
            List<String> paramNames = new ArrayList<>(parameterCount);
            for (int i = 0; i < parameterCount; i++) {
                paramNames.add("");
            }
            return paramNames;
        }

        @Override
        public String symbolNameForMethod() {
            return NativeImage.localSymbolNameForMethod(method);
        }

        @Override
        public boolean isDeoptTarget() {
            if (method instanceof HostedMethod) {
                return ((HostedMethod) method).isDeoptTarget();
            }
            return name().endsWith(HostedMethod.METHOD_NAME_DEOPT_SUFFIX);
        }

        @Override
        public int addressLo() {
            throw GraalError.unimplemented();
        }

        @Override
        public int addressHi() {
            throw GraalError.unimplemented();
        }

        @Override
        public int line() {
            LineNumberTable lineNumberTable = method.getLineNumberTable();
            if (lineNumberTable != null) {
                return lineNumberTable.getLineNumber(bci);
            }
            return -1;
        }

        @Override
        public DebugInfoProvider.DebugLineInfo getCaller() {
            throw GraalError.unimplemented();
        }

        private void computeFullFilePath() {
            ResolvedJavaType declaringClass;
            // if we have a HostedMethod then deal with substitutions
            if (method instanceof HostedMethod) {
                declaringClass = getDeclaringClass((HostedMethod) method, false);
            } else {
                declaringClass = method.getDeclaringClass();
            }
            Class<?> clazz = null;
            if (declaringClass instanceof OriginalClassProvider) {
                clazz = ((OriginalClassProvider) declaringClass).getJavaClass();
            }
            SourceManager sourceManager = ImageSingletons.lookup(SourceManager.class);
            try (DebugContext.Scope s = debugContext.scope("DebugCodeInfo", declaringClass)) {
                fullFilePath = sourceManager.findAndCacheSource(declaringClass, clazz, debugContext);
            } catch (Throwable e) {
                throw debugContext.handle(e);
            }
        }
    }

}

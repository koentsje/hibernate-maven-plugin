/*
 * Copyright 20024 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hibernate.orm.tooling.maven.enhance;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.hibernate.bytecode.enhance.spi.EnhancementException;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.internal.BytecodeProviderInitiator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven mojo for performing build-time enhancement of entity objects.
 */
@Mojo(name = "enhance", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class EnhanceMojo extends AbstractMojo {

	private List<File> sourceSet = new ArrayList<File>();
    private Enhancer enhancer;

    @Parameter
    private FileSet[] fileSets;

    @Parameter(
			defaultValue = "${project.build.directory}/classes", 
			readonly = true, 
			required = true)
    private File classesDirectory;

    @Parameter(
            defaultValue = "false",
            readonly = true,
            required = true)
    private boolean enableAssociationManagement;

    @Parameter(
            defaultValue = "false",
            readonly = true,
            required = true)
    private boolean enableDirtyTracking;

    @Parameter(
            defaultValue = "false",
            readonly = true,
            required = true)
    private boolean enableLazyInitialization;

    @Parameter(
        defaultValue = "false",
        readonly = true,
        required = true)
    private boolean enableExtendedEnhancement;

    public void execute() {
        getLog().debug("Starting execution of enhance mojo");
        processParameters();
        assembleSourceSet();
        createEnhancer();
        discoverTypes();
        performEnhancement();
        getLog().debug("Ending execution of enhance mojo");
    }

    private void processParameters() {
        if (!enableLazyInitialization) {
			getLog().warn( "The 'enableLazyInitialization' configuration is deprecated and will be removed. Set the value to 'true' to get rid of this warning" );
		}
		if (!enableDirtyTracking) {
			getLog().warn( "The 'enableDirtyTracking' configuration is deprecated and will be removed. Set the value to 'true' to get rid of this warning" );
		}
        if (fileSets == null) {
            fileSets = new FileSet[1];
            fileSets[0] = new FileSet();
            fileSets[0].setDirectory(classesDirectory.getAbsolutePath());
            getLog().debug("Addded a default FileSet with base directory: " + fileSets[0].getDirectory());
        }
    }

    private void assembleSourceSet() {
        getLog().debug("Starting assembly of the source set");
        for (FileSet fileSet : fileSets) {
            addFileSetToSourceSet(fileSet);
        }
        getLog().debug("Ending the assembly of the source set");
    }

    private void addFileSetToSourceSet(FileSet fileSet) {
        getLog().debug("Processing FileSet");
        String directory = fileSet.getDirectory();
        FileSetManager fileSetManager = new FileSetManager();
        File baseDir = classesDirectory;
        if (directory != null && classesDirectory != null) {
            baseDir = new File(directory);
        } 
        getLog().debug("Using base directory: " + baseDir);
        for (String fileName : fileSetManager.getIncludedFiles(fileSet)) {
            File candidateFile = new File(baseDir, fileName); 
            if (fileName.endsWith(".class")) {
                sourceSet.add(candidateFile);
                getLog().info("Added file to source set: " + candidateFile);
            } else {
                getLog().debug("Skipping non '.class' file: " + candidateFile);
            }
        }
        getLog().debug("FileSet was processed succesfully");
    }

    private ClassLoader createClassLoader() {
        getLog().debug("Creating URL ClassLoader for folder: " + classesDirectory) ;
		List<URL> urls = new ArrayList<>();
        try {
            urls.add(classesDirectory.toURI().toURL());
        } catch (MalformedURLException e) {
            getLog().error("An unexpected error occurred while constructing the classloader", e);
        }
		return new URLClassLoader(
            urls.toArray(new URL[urls.size()]), 
            Enhancer.class.getClassLoader());
	}

    private EnhancementContext createEnhancementContext() {
        getLog().debug("Creating enhancement context") ;
        return new EnhancementContext(
            createClassLoader(), 
            enableAssociationManagement, 
            enableDirtyTracking, 
            enableLazyInitialization, 
            enableExtendedEnhancement);
    }

    private void createEnhancer() {
        getLog().debug("Creating bytecode enhancer") ;
        enhancer = BytecodeProviderInitiator
            .buildDefaultBytecodeProvider()
            .getEnhancer(createEnhancementContext());
    }

    private void discoverTypes() {
        getLog().debug(STARTING_TYPE_DISCOVERY) ;
        for (File classFile : sourceSet) {
            discoverTypesForClass(classFile);
        }
        getLog().debug(ENDING_TYPE_DISCOVERY) ;
    }

    private void discoverTypesForClass(File classFile) {
        getLog().debug(TRYING_TO_DISCOVER_TYPES_FOR_CLASS_FILE.formatted(classFile));
        try {
            enhancer.discoverTypes(
                determineClassName(classFile), 
                Files.readAllBytes( classFile.toPath()));
            getLog().info(SUCCESFULLY_DISCOVERED_TYPES_FOR_CLASS_FILE.formatted(classFile));
        } catch (IOException e) {
            getLog().error(UNABLE_TO_DISCOVER_TYPES_FOR_CLASS_FILE.formatted(classFile), e);
        }
    }

    private String determineClassName(File classFile) {
        getLog().debug(DETERMINE_CLASS_NAME_FOR_FILE.formatted(classFile));
        String classFilePath = classFile.getAbsolutePath();
        String classesDirectoryPath = classesDirectory.getAbsolutePath();
        return classFilePath.substring(
                classesDirectoryPath.length() + 1,
                classFilePath.length() - ".class".length())
            .replace(File.separatorChar, '.');
    }

     private void performEnhancement() {
        getLog().debug(STARTING_CLASS_ENHANCEMENT) ;
        for (File classFile : sourceSet) {
            long lastModified = classFile.lastModified();
            enhanceClass(classFile);
            final boolean timestampReset = classFile.setLastModified( lastModified );
            if ( !timestampReset ) {
                getLog().debug(SETTING_LASTMODIFIED_FAILED_FOR_CLASS_FILE.formatted(classFile));
            }
        }
        getLog().debug(ENDING_CLASS_ENHANCEMENT) ;
     }

    private void enhanceClass(File classFile) {
        getLog().debug(TRYING_TO_ENHANCE_CLASS_FILE.formatted(classFile));
        try {
            byte[] newBytes = enhancer.enhance(
                determineClassName(classFile), 
                Files.readAllBytes(classFile.toPath()));
            if (newBytes != null) {
                writeByteCodeToFile(newBytes, classFile);
                getLog().info(SUCCESFULLY_ENHANCED_CLASS_FILE.formatted(classFile));
            } else {
                getLog().info(SKIPPING_FILE.formatted(classFile));
            }
        } catch (EnhancementException | IOException e) {
            getLog().error(ERROR_WHILE_ENHANCING_CLASS_FILE.formatted(classFile), e);;
         }
    }

    private void writeByteCodeToFile(byte[] bytes, File file) {
        getLog().debug(WRITING_BYTE_CODE_TO_FILE.formatted(file));
        if (clearFile(file)) {
            try {
                Files.write( file.toPath(), bytes);
                getLog().debug(AMOUNT_BYTES_WRITTEN_TO_FILE.formatted(bytes.length, file));
            }
            catch (FileNotFoundException e) {
                getLog().error(ERROR_OPENING_FILE_FOR_WRITING.formatted(file), e );
            }
            catch (IOException e) {
                getLog().error(ERROR_WRITING_BYTES_TO_FILE.formatted(file), e );
            }
        }
    }

    private boolean clearFile(File file) {
        getLog().debug(TRYING_TO_CLEAR_FILE.formatted(file));
        boolean success = false;
        if ( file.delete() ) {
            try {
                if ( !file.createNewFile() ) {
                    getLog().error(UNABLE_TO_CREATE_FILE.formatted(file));
                } else {
                    getLog().info(SUCCESFULLY_CLEARED_FILE.formatted(file));
                    success = true;
                }
            }
            catch (IOException e) {
                getLog().warn(PROBLEM_CLEARING_FILE.formatted(file), e);
            }
        }
        else {
            getLog().error(UNABLE_TO_DELETE_FILE.formatted(file));
        }
    return success;
    }
    
    // info messages
    static final String SUCCESFULLY_CLEARED_FILE = "Succesfully cleared the contents of file: %s";
    static final String SUCCESFULLY_ENHANCED_CLASS_FILE = "Succesfully enhanced class file: %s";
    static final String SKIPPING_FILE = "Skipping file: %s";
    static final String SUCCESFULLY_DISCOVERED_TYPES_FOR_CLASS_FILE = "Succesfully discovered types for classes in file: %s";
    
    // warning messages
    static final String PROBLEM_CLEARING_FILE = "Problem clearing file for writing out enhancements [ %s ]";
    
    // error messages
    static final String UNABLE_TO_CREATE_FILE = "Unable to create file: %s"; 
    static final String UNABLE_TO_DELETE_FILE = "Unable to delete file: %s"; 
    static final String ERROR_WRITING_BYTES_TO_FILE = "Error writing bytes to file : %s";
    static final String ERROR_OPENING_FILE_FOR_WRITING = "Error opening file for writing : %s";
    static final String ERROR_WHILE_ENHANCING_CLASS_FILE = "An exception occurred while trying to class file: %s";
    static final String UNABLE_TO_DISCOVER_TYPES_FOR_CLASS_FILE = "Unable to discover types for classes in file: %s";
    
    // debug messages
    static final String TRYING_TO_CLEAR_FILE = "Trying to clear the contents of file: %s";
    static final String AMOUNT_BYTES_WRITTEN_TO_FILE = "%s bytes were succesfully written to file: %s";
    static final String WRITING_BYTE_CODE_TO_FILE = "Writing byte code to file: %s";
    static final String DETERMINE_CLASS_NAME_FOR_FILE = "Determining class name for file: %s";
    static final String TRYING_TO_ENHANCE_CLASS_FILE = "Trying to enhance class file: %s";
    static final String STARTING_CLASS_ENHANCEMENT = "Starting class enhancement";
    static final String SETTING_LASTMODIFIED_FAILED_FOR_CLASS_FILE = "Setting lastModified failed for class file: %s";
    static final String ENDING_CLASS_ENHANCEMENT = "Ending class enhancement";
    static final String TRYING_TO_DISCOVER_TYPES_FOR_CLASS_FILE = "Trying to discover types for classes in file: %s";
    static final String STARTING_TYPE_DISCOVERY = "Starting type discovery";
    static final String ENDING_TYPE_DISCOVERY = "Ending type discovery";

}

/*
 * Created on 07/09/2005
 */
package com.python.pydev.analysis.additionalinfo;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.Document;
import org.python.parser.SimpleNode;
import org.python.pydev.core.REF;
import org.python.pydev.editor.codecompletion.revisited.ModulesManager;
import org.python.pydev.editor.codecompletion.revisited.PythonPathHelper;
import org.python.pydev.editor.codecompletion.revisited.SystemModulesManager;
import org.python.pydev.editor.codecompletion.revisited.modules.ModulesKey;
import org.python.pydev.parser.PyParser;
import org.python.pydev.plugin.PydevPlugin;
import org.python.pydev.plugin.nature.PythonNature;
import org.python.pydev.ui.NotConfiguredInterpreterException;
import org.python.pydev.ui.interpreters.IInterpreterManager;
import org.python.pydev.ui.interpreters.IInterpreterObserver;
import org.python.pydev.ui.pythonpathconf.InterpreterInfo;
import org.python.pydev.utils.JobProgressComunicator;


public class InterpreterObserver implements IInterpreterObserver {

    private static final boolean DEBUG_INTERPRETER_OBSERVER = false;

    /**
     * @see org.python.pydev.ui.interpreters.IInterpreterObserver#notifyDefaultPythonpathRestored(org.python.pydev.ui.interpreters.AbstractInterpreterManager, org.eclipse.core.runtime.IProgressMonitor)
     */
    public void notifyDefaultPythonpathRestored(IInterpreterManager manager, IProgressMonitor monitor) {
        if(DEBUG_INTERPRETER_OBSERVER){
            System.out.println("notifyDefaultPythonpathRestored "+ manager.getDefaultInterpreter());
        }
        try {
            InterpreterInfo defaultInterpreterInfo = manager.getDefaultInterpreterInfo(monitor);
            SystemModulesManager m = defaultInterpreterInfo.modulesManager;
            AbstractAdditionalInterpreterInfo additionalSystemInfo = restoreInfoForModuleManager(monitor, m, "(system: "+manager.getManagerRelatedName()+")", new AdditionalSystemInterpreterInfo(manager));

            //ok, set it and save it
            AdditionalSystemInterpreterInfo.setAdditionalSystemInfo(manager, additionalSystemInfo);
            AbstractAdditionalInterpreterInfo.saveAdditionalSystemInfo(manager);
        } catch (NotConfiguredInterpreterException e) {
            //ok, nothing configured, nothing to do...
        }
    }

    /**
     * received when the interpreter manager is restored
     *  
     * this means that we have to restore the additional interpreter information we stored
     *  
     * @see org.python.pydev.ui.interpreters.IInterpreterObserver#notifyInterpreterManagerRecreated(org.python.pydev.ui.interpreters.AbstractInterpreterManager)
     */
    public void notifyInterpreterManagerRecreated(final IInterpreterManager manager) {
        if(!AdditionalSystemInterpreterInfo.loadAdditionalSystemInfo(manager)){
            //not successfully loaded
            Job j = new Job("Pydev... Restoring additional info"){

                
                @Override
                protected IStatus run(IProgressMonitor monitorArg) {
                    JobProgressComunicator jobProgressComunicator = new JobProgressComunicator(monitorArg, "Pydev... Restoring additional info", IProgressMonitor.UNKNOWN, this);
                    notifyDefaultPythonpathRestored(manager, jobProgressComunicator);
                    jobProgressComunicator.done();
                    return Status.OK_STATUS;
                }
                
            };
            j.setPriority(Job.BUILD);
            j.schedule();
        }
    }

    /**
     * Restores the info for a module manager
     * 
     * @param monitor a monitor to keep track of the progress
     * @param m the module manager
     * @return the info generated from the module manager
     */
    private AbstractAdditionalInterpreterInfo restoreInfoForModuleManager(IProgressMonitor monitor, ModulesManager m, String additionalFeedback, AbstractAdditionalInterpreterInfo info) {

        ModulesKey[] allModules = m.getOnlyDirectModules();
        int i = 0;
        for (ModulesKey key : allModules) {
            i++;

            if (key.file != null) { //otherwise it should be treated as a compiled module (no ast generation)

                if (key.file.exists()) {

                    if (PythonPathHelper.isValidSourceFile(REF.getFileAbsolutePath(key.file))) {
                        StringBuffer buffer = new StringBuffer();
                        buffer.append("Creating ");
                        buffer.append(additionalFeedback);
                        buffer.append(" additional info (" );
                        buffer.append(i );
                        buffer.append(" of " );
                        buffer.append(allModules.length );
                        buffer.append(") for " );
                        buffer.append(key.file.getName());
                        monitor.setTaskName(buffer.toString());
                        monitor.worked(1);

                        try {
                            
                            //  the code below works with the default parser (that has much more info... and is much slower)
                            PyParser.ParserInfo parserInfo = new PyParser.ParserInfo(new Document(REF.getFileContents(key.file)), false, null);
                            Object[] obj = PyParser.reparseDocument(parserInfo);
                            SimpleNode node = (SimpleNode) obj[0];

                            //maybe later we can change by this, if it becomes faster and more consistent
                            //SimpleNode node = FastParser.reparseDocument(REF.getFileContents(key.file));

                            if (node != null) {
                                info.addAstInfo(node, key.name);
                            }else{
                                throw new RuntimeException("Unable to generate ast.");
                            }

                        } catch (Exception e) {
                            PydevPlugin.log(IStatus.ERROR, "Problem parsing the file :" + key.file + ".", e);
                        }
                    }
                } else {
                    PydevPlugin.log("The file :" + key.file + " does not exist, but is marked as existing in the pydev code completion.");
                }
            }
        }
        return info;
    }


    public void notifyProjectPythonpathRestored(final PythonNature nature, IProgressMonitor monitor) {
        ModulesManager m = nature.getAstManager().getProjectModulesManager();
        IProject project = nature.getProject();
        AbstractAdditionalInterpreterInfo info = restoreInfoForModuleManager(monitor, m, "(project:"+project.getName()+")", new AdditionalProjectInterpreterInfo(project));
        
        //ok, set it and save it
        AdditionalProjectInterpreterInfo.setAdditionalInfoForProject(project, info);
        AdditionalProjectInterpreterInfo.saveAdditionalInfoForProject(project);
    }

    public void notifyNatureRecreated(final PythonNature nature, IProgressMonitor monitor) {
        if(!AdditionalProjectInterpreterInfo.loadAdditionalInfoForProject(nature.getProject())){
            if(DEBUG_INTERPRETER_OBSERVER){
                System.out.println("Unable to load the info correctly... restoring info from the pythonpath");
            }
            notifyProjectPythonpathRestored(nature, monitor);
        }
    }

}

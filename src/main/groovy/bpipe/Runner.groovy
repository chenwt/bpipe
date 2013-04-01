/*
 * Copyright (c) Murdoch Childrens Research Institute and Contributers
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */  
package bpipe

import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Handler;
import java.util.logging.Level
import java.util.logging.Logger;

import groovy.transform.CompileStatic;
import groovy.util.logging.Log

/**
 * The main entry point for Bpipe.
 * <p>
 * Handles parsing and validation of command line parameters and flags,
 * reads the user script and creates a Groovy shell to run the script
 * with variables initialized correctly.
 * 
 * @author simon.sadedin@mcri.edu.au
 */
class Runner {
    
    private static Logger log = Logger.getLogger("bpipe.Runner")
    
    final static String version = System.getProperty("bpipe.version")
    
    final static String builddate = System.getProperty("bpipe.builddate")?:System.currentTimeMillis()
    
    
    final static String DEFAULT_HELP = """
        bpipe [run|test|debug|execute] [options] <pipeline> <in1> <in2>...
              retry [test]
              stop
              history 
              log
              jobs
              cleanup
              query
              preserve
              diagram <pipeline> <in1> <in2>...
              diagrameditor <pipeline> <in1> <in2>...
    """.stripIndent().trim()
    
    static CliBuilder runCli = new CliBuilder(usage: DEFAULT_HELP, posix: true)
          
    static CliBuilder stopCommandsCli = new CliBuilder(usage: "bpipe stopcommands\n", posix: true)
    
    static CliBuilder diagramCli = new CliBuilder(usage: "bpipe diagram [-e] <pipeline> <input1> <input2> ...\n", posix: true)
    
    public static OptionAccessor opts = runCli.parse([])
    
    public static void main(String [] args) {
        
        def db = new File(".bpipe")
        if(!db.exists())
            if(!db.mkdir())
                throw new RuntimeException("Bpipe was not able to make its database directory, .bpipe in the local folder.  Is this folder read-only?")
        
        String pid = resolvePID()
        
        Config.config.pid = pid
        Config.config.outputLogPath = ".bpipe/logs/${pid}.log"
            
        // PID of shell that launched Bpipe
        String parentPid = System.getProperty("bpipe.pid")
        
        // Before we do anything else, add a shutdown hook so that termination of the process causes the job to 
        // to be removed from the user's folder
        System.addShutdownHook { 
            def home = System.getProperty("user.home")
            def jobFile = new File("$home/.bpipedb/jobs/$pid")
            if(jobFile.exists()) {
                log.info("Deleting job file $jobFile")
                if(!jobFile.delete()) {
                    log.warning("Unable to delete job file for job $pid")
                    println("WARN: Unable to delete job file for job $pid")
                }
            }
            
            if(Config.config.eraseLogsOnExit) {
                new File(".bpipe/logs/${parentPid}.erase.log").text=""
            }
        }
                
        def parentLog = initializeLogging(pid)
        
        // read the configuration file, if available
        try {
            Config.readUserConfig()
        }
        catch( Exception e ) {
            def cause = e.getCause() ?: e
            println("Error parsing 'bpipe.config' file. Cause: ${cause.getMessage() ?: cause}")
            System.exit(1)
        }
        
        def cli 
        String mode = System.getProperty("bpipe.mode")
        if(mode == "diagram")  {
            log.info("Mode is diagram")
            cli = diagramCli
            Config.config["mode"] = "diagram"
        }
        else
        if(mode == "documentation")  {
            log.info("Mode is documentation")
            cli = diagramCli
            Config.config["mode"] = "documentation"
        }
        else 
        if(mode == "diagrameditor") {
            log.info("Mode is diagram editor")
            cli = diagramCli
            Config.config["mode"] = "diagrameditor"
        }
        else 
        if(mode == "cleanup") {
            runCleanup(args)
        }         
        else 
        if(mode == "query") {
            log.info("Showing dependency graph for " + args)
            Dependencies.instance.queryOutputs(args)
            System.exit(0)
        }         
        else
        if(mode == "preserve") {
            log.info("Preserving " + args)
            this.runPreserve(args)
            System.exit(0)
        } 
        else
        if(mode == "stopcommands") {
            log.info("Stopping running commands")
            cli = stopCommandsCli
            Config.config["mode"] = "stopcommands"
            int count = new CommandManager().stopAll()
            println "Stopped $count commands"
            System.exit(0)
        } 
        else {
            
            if(mode == "retry") {
                // Substitute arguments from prior command 
                // to re-run it
                def retryInfo = parseRetryArgs(args)
                args = retryInfo[1]
                mode = retryInfo[0]
            }
            
            cli = runCli
            cli.with {
                 h longOpt:'help', 'usage information'
                 d longOpt:'dir', 'output directory', args:1
                 t longOpt:'test', 'test mode'
                 r longOpt:'report', 'generate an HTML report / documentation for pipeline'
                 n longOpt:'threads', 'maximum threads', args:1
                 m longOpt:'memory', 'maximum memory', args:1
                 l longOpt:'resource', 'place limit on named resource', args:1, argName: 'resource=value'
                 v longOpt:'verbose', 'print internal logging to standard error'
                 y longOpt:'yes', 'answer yes to any prompts or questions'
                 p longOpt: 'param', 'defines a pipeline parameter', args: 1, argName: 'param=value', valueSeparator: ',' as char
                 'L' longOpt: 'interval', 'the default genomic interval to execute pipeline for (samtools format)',args: 1
            }
        }
        
        String versionInfo = "\nBpipe Version $version   Built on ${new Date(Long.parseLong(builddate))}\n"
        
        def opt = cli.parse(args)
        if(!opt) 
            System.exit(1)
            
        if(!opt.arguments()) {
            println versionInfo
            cli.usage()
            println "\n"
            System.exit(1)
        }
        
        opts = opt
        if(opts.v) {
            ConsoleHandler console = new ConsoleHandler()
            console.setFormatter(new BpipeLogFormatter())
            console.setLevel(Level.FINE)
            parentLog.addHandler(console)
        }
        
        if(opts.d) {
            Config.config.defaultOutputDirectory = opts.d
        }
        
        if(opts.n) {
            log.info "Maximum threads specified as $opts.n"
            Config.config.maxThreads = Integer.parseInt(opts.n)
            Config.config.customThreads = true
        }
        
        if(opts.m) {
            log.info "Maximum memory specified as $opts.m MB"
            Config.config.maxMemoryMB = Integer.parseInt(opts.m)
        }
        
        if(opts.l) {
            log.info "Resource limit specified as $opts.l"
            def limit = opts.l.split("=")
            if(limit.size()!=2) {
                System.err.println "\nBad format for limit $opts.l - expect format <name>=<value>\n"
                cli.usage()
                System.exit(1)
            }
            Concurrency.instance.setLimit(limit[0],limit[1] as Integer)
        }
        
        if(opts.r) {
            Config.config.report = true
            def reportStats = new ReportStatisticsListener()
            EventManager.instance.addListener(PipelineEvent.STAGE_STARTED, reportStats)
            EventManager.instance.addListener(PipelineEvent.STAGE_COMPLETED, reportStats)
        }

        def pipelineArgs = null
        String pipelineSrc
        if(mode == "execute") {
            pipelineSrc = 'Bpipe.run { ' + opt.arguments()[0] + '}'
        }
        else {
            pipelineSrc = loadPipelineSrc(cli, opt.arguments()[0])
        }
        
        if(opt.arguments().size() > 1)
            pipelineArgs = opt.arguments()[1..-1]
                

        ToolDatabase.instance.init(Config.userConfig)
        
        // Add event listeners that come directly from configuration
        EventManager.instance.configure(Config.userConfig)
		
		if(!opts.t)
			NotificationManager.instance.configure(Config.userConfig)

        // If we got this far and are not in test mode, then it's time to 
        // make the logs stick around
        if(!opts.t) {
            Config.config.eraseLogsOnExit = false
            appendCommandToHistoryFile(mode, args, pid)
        }

        def gcl = new GroovyClassLoader()

        // add all user specified parameters to the binding
        ParamsBinding binding = new ParamsBinding()
        if( opts.params ) {  // <-- note: ending with the 's' character the 'param' option, will force to return it as list of string
            log.info "Adding CLI parameters: ${opts.params}"
               
            binding.addParams( opts.params )
        }
        else {
            log.info "No CLI parameters specified"
        }
        
        if(opts.L) 
            binding.setParam("region", new RegionValue(value: opts.L))

        // create the pipeline script instance and set the binding obj
        Script script = gcl.parseClass(pipelineSrc).newInstance()
        script.setBinding(binding)
        // set the pipeline arguments
        script.setProperty("args", pipelineArgs);

        // RUN it
        try {
            script.run()
        }
        catch(MissingPropertyException e)  {
            if(e.type.name.startsWith("script")) {
                // Handle this as a user error in defining their script
                // print a nicer error message than what comes out of groovy by default
                handleMissingPropertyFromPipelineScript(e)
            }
            else
                throw e
        }
    }

    private static handleMissingPropertyFromPipelineScript(MissingPropertyException e) {
        // A bit of a hack: the parsed script ends up with a class name like script123243242...
        // so search for that in the stack trace to find the line number
        int lineNumber = e.stackTrace.find { it.className ==~ /script[0-9]{1,}/ }.lineNumber

        println """
                    Pipeline Failed!

                    A variable referred to in your script on line ${lineNumber}, '$e.property' was not defined.  
                    
                    Please check that all pipeline stages or other variables you have referenced by this name are defined.
                    """.stripIndent()
    }

    /**
     * Set up logging for the Bpipe diagnostic log
     */
    public static Logger initializeLogging(String pid) {
        
        def parentLog = log.getParent()
        parentLog.getHandlers().each { parentLog.removeHandler(it) }

        // The current log file
        FileHandler fh = new FileHandler(".bpipe/bpipe.log")
        fh.setFormatter(new BpipeLogFormatter())
        parentLog.addHandler(fh)

        // Another log file for history
        new File(".bpipe/logs").mkdirs()
        Handler pidLog = pid == "tests" ? new ConsoleHandler() : new FileHandler(".bpipe/logs/${pid}.bpipe.log")
        pidLog.setFormatter(new BpipeLogFormatter())
        parentLog.addHandler(pidLog)

		Properties p = System.properties
        log.info("Starting")
        log.info("OS: " + p['os.name'] + " (" + p['os.version'] + ") Java: " + p['java.version'] + " Vendor: " + p["java.vendor"] )
		
        return parentLog
    }


    
    /**
     * Try to determine the process id of this Java process.
     * Because the PID is read from a file that is created after
     * starting of the Java process there is a race condition and thus
     * this call *may* wait some (small) time for the file to appear.
     * <p>
     * TODO: it would probably be possible to remove this now and pass the PID
     *       as a system property
     * 
     * @return    process ID of our process
     */
    private static String resolvePID() {
        // If we weren't given a host pid, assume we are running as a generic
        // command and just put the log files, etc, under this name
        String pid = "command"

        // This property is stored as a file by the hosting bash script
        String ourPid = System.getProperty("bpipe.pid")
        if(ourPid) {
            File pidFile = new File(".bpipe/launch/${ourPid}")
            int count = 0
            while(true) {
                if(pidFile.exists()) {
                    pid = pidFile.text.trim()
                    pidFile.delete()
                    break
                }

                if(count > 100) {
                    println "ERROR: Bpipe was unable to read its startup PID file from $pidFile.absolutePath"
                    println "ERROR: This may indicate you are in a read-only directory or one to which you do not have full permissions"
                    System.exit(1)
                }

                // Spin a short time waiting
                Thread.sleep(20)
                ++count
            }
        }
        return pid
    }
                    
    /**
     * Loads a pipeline file from the source path, checks it in some simple ways and 
     * augments it with some precursor declarations and imports to bring things into
     * the default scope.
     * 
     * @param cli
     * @param srcFilePath
     * @return
     */
    static String loadPipelineSrc(def cli, def srcFilePath) {
        File pipelineFile = new File(srcFilePath)
        if(!pipelineFile.exists()) {
            println "\nCould not understand command $pipelineFile or find it as a file\n"
            cli.usage()
            println "\n"
            System.exit(1)
        }
        
        // Note that it is important to keep this on a single line because 
        // we want any errors in parsing the script to report the correct line number
        // matching what the user sees in their script
        String pipelineSrc = Pipeline.PIPELINE_IMPORTS + pipelineFile.text
        if(pipelineFile.text.indexOf("return null") >= 0) {
            println """
                       ================================================================================================================
                       | WARNING: since 0.9.4 using 'return null' in pipeline stages is incorrect. Please use 'forward input' instead.|
                       ================================================================================================================
            """.stripIndent()
        }
        return pipelineSrc
    }
    
    
    /**
     * Execute the 'cleanup' command
     * @param args
     */
    static void runCleanup(def args) {
        def cli = new CliBuilder(usage: "bpipe cleanup [-y]\n", posix: true)
        cli.with {
            y longOpt: 'yes', 'answer yes to any prompts or questions'
        }
        def opt = cli.parse(args)
        if(opt.y) {
            Config.userConfig.prompts.handler = { msg -> return "y"}
        }
            
        Dependencies.instance.cleanup(opt.arguments())
        System.exit(0)
    }
    
    /**
     * Execute the 'preserve' command
     * @param args
     */
    static void runPreserve(def args) {
        def cli = new CliBuilder(usage: "bpipe preserve <file1> [<file2>] ...")
        def opt = cli.parse(args)
        if(!opt.arguments()) {
            println ""
            cli.usage()
            System.exit(1)
        }
        Dependencies.instance.preserve(opt.arguments())
        System.exit(0)
    }
    
    /**
     * Parse the arguments from the retry command to see if the user has 
     * specified a job or test mode, then find the relevant command
     * from history and return it
     * 
     * @param args  arguments passed to retry 
     * @return  a list with 2 elements, [ <command>, <arguments> ]
     */
    static def parseRetryArgs(args) {
        
        // They come in as an array, but there are some things that don't work
        // on arrays in groovy ... (native java list operations)
        args = args as List
        
        String notFoundMsg = """
            
            No previous Bpipe command seems to have been run in this directory."

            """.stripIndent()
            
            
        String usageMsg = """
           Usage: bpipe retry [jobid] [test]
        """.stripIndent()
        
        def historyFile = new File(".bpipe/history")
        if(!historyFile.exists()) {
            System.err.println(notFoundMsg);
            System.exit(1)
        }
        
        def historyLines = historyFile.readLines()
        if(!historyLines) {
            System.err.println(notFoundMsg);
            System.exit(1)
        }
        
        String commandLine = null
        boolean testMode = false
        if(!args) {
            commandLine = historyLines[-1]
        }
        else {
            if(args[0] == "test") {
                testMode = true
                args.remove(0)
            }
            
            if(args) {
                if(args[0].isInteger()) {
                  commandLine = historyLines.reverse().find { it.startsWith(args[0] + "\t") }
                }
                else {
                    System.err.println "\nJob ID could not be parsed as integer\n" + usageMsg
                    System.exit(1)
                }
            }
            else {
                commandLine = historyLines[-1]
            }
        }
        
        // Trim off the job id
        if(commandLine.matches("^[0-9]{1,6}.*\$"))
            commandLine = commandLine.substring(commandLine.indexOf("\t")+1)
        
        // Remove leading "bpipe" and "run" arguments
        def parsed = (commandLine =~ /bpipe ([a-z]*) (.*)$/)
        if(!parsed)
            throw new PipelineError("Internal error: failed to understand format of command from history:\n\n$commandLine\n")
            
        args = Utils.splitShellArgs(parsed[0][2]) 
        def command = parsed[0][1]
        
        return [ command,  testMode ? ["-t"] + args : args]
    }
    
    /**
     * Add a line to the current history file with information about
     * this run. The current command is stored in .bpipe/lastcmd
     */
    static void appendCommandToHistoryFile(String mode, args, String pid) {
        
        File history = new File(".bpipe/history")
        if(!history.exists())
            history.text = ""
            
        if(mode == null)
            mode = "run"
            
        history.withWriterAppend { it << [pid, "bpipe $mode " + args.collect { it.contains(" ") ? "'$it'" : it }.join(" ")].join("\t") + "\n" }
    }
}

/**
 * Custom binding used to hold the CLI specified parameters.
 * <p>
 * The difference respect the default implementation is that
 * once the value is defined it cannot be overridden, so this make
 * the parameters definition works like constant values.
 * <p>
 * The main reason for that is to be able to provide optional default value
 * for script parameters in the pipeline script.
 *
 * Read more about 'binding variables'
 * http://groovy.codehaus.org/Scoping+and+the+Semantics+of+%22def%22
 *
 */
@Log
class ParamsBinding extends Binding {
    
    def parameters = []

    def void setParam( String name, Object value ) {

        // mark this name as a parameter
        if( !parameters.contains(name) ) {
            parameters.add(name)
        }

        super.setVariable(name,value)
    }

    def void setVariable(String name, Object value) {

        // variable name marked as parameter cannot be overridden
        if( name in parameters ) {
            return
        }

        super.setVariable(name,value)
    }

    /**
     * Add as list of key-value pairs as binding parameters
     * <p>See {@link #setParam}
     *
     * @param listOfKeyValuePairs
     */
    def void addParams( List<String> listOfKeyValuePairs ) {

        if( !listOfKeyValuePairs ) return

        listOfKeyValuePairs.each { pair ->
            MapEntry entry = parseParam(pair)
            if( !entry ) {
                log.warning("The specified value is a valid parameter: '${pair}'. It must be in format 'key=value'")
            }
            else {
                if(entry.key == "region") {
                    setParam(entry.key, new RegionValue(value:entry.value))
                }
                else
                  setParam(entry.key, entry.value)
            }
        }
    }


    /**
     * Parse a key-value pair with the following syntax
     * <code>key = value </code>
     *
     * @param item The key value string
     * @return A {@link MapEntry} instance
     */
    static MapEntry parseParam( String item ) {
        if( !item ) return null

        def p = item.indexOf('=')
        def key
        def value
        if( p != -1 )  {
            key = item.substring(0,p)
            value = item.substring(p+1)

        }
        else {
            key = item
            value = null
        }

        if( !key ) {
            // the key is mandatory
            return null
        }

        if( !value ) {
            value = true
        }
        else {
            if( value.isInteger() ) {
                value = value.toInteger()
            }
            else if( value.isLong() ) {
                value = value.toLong()
            }
            else if( value.isDouble() ) {
                value = value.toDouble()
            }
            else if( value.toLowerCase() in ['true','false'] ) {
                value = Boolean.parseBoolean(value.toLowerCase())
            }
        }

        new MapEntry( key, value )
    }
}

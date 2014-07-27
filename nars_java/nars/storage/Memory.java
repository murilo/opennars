/*
 * Memory.java
 *
 * Copyright (C) 2008  Pei Wang
 *
 * This file is part of Open-NARS.
 *
 * Open-NARS is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * Open-NARS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Open-NARS.  If not, see <http://www.gnu.org/licenses/>.
 */
package nars.storage;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import nars.core.NAR;
import nars.core.Parameters;
import nars.entity.BudgetValue;
import nars.entity.Concept;
import nars.entity.Item;
import nars.entity.Sentence;
import nars.entity.Stamp;
import nars.entity.Task;
import nars.entity.TaskLink;
import nars.entity.TermLink;
import nars.entity.TruthValue;
import nars.inference.BudgetFunctions;
import nars.inference.InferenceRecorder;
import nars.inference.TemporalRules;
import nars.io.Output.OUT;
import nars.language.Term;


public class Memory {

    public static Random randomNumber = new Random(1);

    /**
     * Backward pointer to the nar
     */
    public final NAR nar;

    /* ---------- Long-term storage for multiple cycles ---------- */
    /**
     * Concept bag. Containing all Concepts of the system
     */
    public final ConceptBag concepts;
    /**
     * New tasks with novel composed terms, for delayed and selective processing
     */
    public final NovelTaskBag novelTasks;
    /**
     * Inference record text to be written into a log file
     */
    private InferenceRecorder recorder;
    
    public final AtomicInteger beliefForgettingRate = new AtomicInteger(Parameters.TERM_LINK_FORGETTING_CYCLE);
    public final AtomicInteger taskForgettingRate = new AtomicInteger(Parameters.TASK_LINK_FORGETTING_CYCLE);
    public final AtomicInteger conceptForgettingRate = new AtomicInteger(Parameters.CONCEPT_FORGETTING_CYCLE);

    /* ---------- Short-term workspace for a single cycle ---	------- */
    /**
     * List of new tasks accumulated in one cycle, to be processed in the next
     * cycle
     */
    public final ArrayDeque<Task> newTasks;

    public Term currentTerm;

    public Concept currentConcept;

    private Task currentTask;

    private TermLink currentBeliefLink;
    private TaskLink currentTaskLink;

    private Sentence currentBelief;

    private Stamp newStamp;
    
    /**
     * The substitution that unify the common term in the Task and the Belief
     * TODO unused
     */
    protected HashMap<Term, Term> substitute;
    

    // for temporal induction
    private Task lastEvent;

    public BudgetValue getLastEventBudget() {
        return lastEvent.budget;
    }


    /* ---------- Constructor ---------- */
    /**
     * Create a new memory
     * <p>
     * Called in Reasoner.reset only
     *
     * @param nar
     */
    public Memory(NAR nar) {
        this.nar = nar;
        recorder = NullInferenceRecorder.global;
        concepts = new ConceptBag(nar.config.getConceptBagLevels(), nar.config.getConceptBagSize(), conceptForgettingRate);
        novelTasks = new NovelTaskBag(nar.config.getConceptBagLevels(), Parameters.TASK_BUFFER_SIZE);
        newTasks = new ArrayDeque<>();
        lastEvent = null;
    }

    public void init() {
        concepts.clear();
        novelTasks.clear();
        newTasks.clear();
        randomNumber = new Random(1);
        lastEvent = null;
        if (getRecorder().isActive()) {
            getRecorder().append("--reset--");
        }
    }

    public InferenceRecorder getRecorder() {
        return recorder;
    }

    public void setRecorder(InferenceRecorder recorder) {
        this.recorder = recorder;
    }

    public long getTime() {
        return nar.getTime();
    }


    /**
     * Actually means that there are no new Tasks
     *
     * @return Whether the newTasks list is empty
     */
    public boolean noResult() {
        return newTasks.isEmpty();
    }

    /* ---------- conversion utilities ---------- */
    /**
     * Get an existing Concept for a given name
     * <p>
     * called from Term and ConceptWindow.
     *
     * @param name the name of a concept
     * @return a Concept or null
     */
    public Concept nameToConcept(final String name) {
        return concepts.get(name);
    }

    /**
     * Get a Term for a given name of a Concept or Operator
     * <p>
     * called in StringParser and the make methods of compound terms.
     *
     * @param name the name of a concept or operator
     * @return a Term or null (if no Concept/Operator has this name)
     */
    public Term nameToTerm(final String name) {
        final Concept concept = concepts.get(name);
        if (concept != null) {
            return concept.term;
        }
        return null;
    }

    /**
     * Get an existing Concept for a given Term.
     *
     * @param term The Term naming a concept
     * @return a Concept or null
     */
    public Concept termToConcept(final Term term) {
        return nameToConcept(term.getName());
    }

    /**
     * Get the Concept associated to a Term, or create it.
     *
     * @param term indicating the concept
     * @return an existing Concept, or a new one, or null ( TODO bad smell )
     */
    public Concept getConcept(final Term term) {
        if (!term.isConstant()) {
            return null;
        }
        final String n = term.getName();
        Concept concept = concepts.get(n);
        if (concept == null) {
            // The only part of NARS that instantiates new Concepts
            concept = new Concept(term, this);

            final boolean created = concepts.putIn(concept);
            if (!created) {
                return null;
            } else {
                if (recorder.isActive()) {
                    recorder.onConceptNew(concept);
                }
            }
        }
        return concept;
    }

    /**
     * Get the current activation level of a concept.
     *
     * @param t The Term naming a concept
     * @return the priority value of the concept
     */
    public float getConceptActivation(final Term t) {
        final Concept c = termToConcept(t);
        return (c == null) ? 0f : c.getPriority();
    }

    /* ---------- adjustment functions ---------- */
    /**
     * Adjust the activation level of a Concept
     * <p>
     * called in Concept.insertTaskLink only
     *
     * @param c the concept to be adjusted
     * @param b the new BudgetValue
     */
    public void activateConcept(final Concept c, final BudgetValue b) {
        concepts.pickOut(c.getKey());
        BudgetFunctions.activate(c, b);
        concepts.putBack(c);
    }

    /* ---------- new task entries ---------- */
    /**
     * add new task that waits to be processed in the next workCycle
     */
    protected void addNewTask(final Task t, final String reason) {
        newTasks.add(t);
        if (recorder.isActive()) {
            recorder.onTaskAdd(t, reason);
        }
    }
    
    /* There are several types of new tasks, all added into the
     newTasks list, to be processed in the next workCycle.
     Some of them are reported and/or logged. */
    /**
     * Input task processing. Invoked by the outside or inside environment.
     * Outside: StringParser (addInput); Inside: Operator (feedback). Input
     * tasks with low priority are ignored, and the others are put into task
     * buffer.
     *
     * @param task The addInput task
     */
    public void inputTask(final Task task) {
        if (task.budget.aboveThreshold()) {
            addNewTask(task, "Perceived");
        } else {
            if (recorder.isActive()) {
                recorder.onTaskRemove(task, "Neglected");
            }
        }
    }

    /**
     * Activated task called in MatchingRules.trySolution and
     * Concept.processGoal
     *
     * @param budget The budget value of the new Task
     * @param sentence The content of the new Task
     * @param candidateBelief The belief to be used in future inference, for
     * forward/backward correspondence
     */
    public void activatedTask(final BudgetValue budget, final Sentence sentence, final Sentence candidateBelief) {
        final Task task = new Task(sentence, budget, getCurrentTask(), sentence, candidateBelief);

        if (sentence.isQuestion()) {
            final float s = task.budget.summary();
            final float minSilent = nar.param.getSilenceLevel() / 100.0f;
            if (s > minSilent) {  // only report significant derived Tasks
                nar.output(OUT.class, task.getSentence());
            }
        }

        addNewTask(task, "Activated");
    }

    /**
     * Derived task comes from the inference rules.
     *
     * @param task the derived task
     */
    public void derivedTask(final Task task, final boolean revised, final boolean single) {
        if (task.budget.aboveThreshold()) {
            if (task.getSentence() != null && task.getSentence().truth != null) {
                float conf = task.getSentence().truth.getConfidence();                
                if (conf == 0) { 
                    //no confidence - we can delete the wrongs out that way.
                    if (recorder.isActive()) {
                        recorder.onTaskRemove(task, "Ignored");
                    }
                    return;
                }
            }
            final Stamp stamp = task.getSentence().stamp;
            final List<Term> chain = stamp.getChain();
            
            final Term currentTaskContent = getCurrentTask().getContent();
            
            if (getCurrentBelief() != null) {
                final Term currentBeliefContent = getCurrentBelief().getContent();
                if(chain.contains(currentBeliefContent)) {
                //if(stamp.chainContainsInstance(currentBeliefContent)) {
                    chain.remove(currentBeliefContent);
                }
                stamp.addToChain(currentBeliefContent);
            }
            
            
            //workaround for single premise task issue:
            if(getCurrentBelief() == null && single && getCurrentTask() != null) {
                if(chain.contains(currentTaskContent)) {
                //if(stamp.chainContainsInstance(currentTaskContent)) {
                    chain.remove(currentTaskContent);
                }
                stamp.addToChain(currentTaskContent);
            }
            //end workaround
            if (getCurrentTask() != null && !single) {
                if(chain.contains(currentTaskContent)) {                
                //if(stamp.chainContainsInstance(currentTaskContent)) {                    
                    chain.remove(currentTaskContent);
                }
                stamp.addToChain(currentTaskContent);
            }
                          
            
            if (!revised) { //its a inference rule, we have to do the derivation chain check to hamper cycles
                for (int i = 0; i < chain.size(); i++) {
                    final Term chain1 = chain.get(i);
                    if (task.getContent() == chain1) {
                        if (recorder.isActive()) {
                            recorder.onTaskRemove(task, "Cyclic Reasoning (index " + i + ")");
                        }
                        return;
                    }
                }
            } else { //its revision, of course its cyclic, apply evidental base policy
                final int stampLength = stamp.baseLength;
                for (int i = 0; i < stampLength; i++) {
                    final long baseI = stamp.evidentialBase[i];
                    
                    for (int j = 0; j < stampLength; j++) {
                        if ((i != j) && (baseI == stamp.evidentialBase[j])) {
                            if (recorder.isActive()) {                                
                                recorder.onTaskRemove(task, "Overlapping Evidence on Revision");
                            }
                            return;
                        }
                    }
                }
            }
            float budget = task.budget.summary();
            float minSilent = nar.param.getSilenceLevel() / 100.0f;
            
            
            //Experiment:  if it has the budget (equal) then it  should be able to buy the output, so changing '>' to '>='           
            if (budget >= minSilent) {  
                // only report significant derived Tasks
                nar.output(OUT.class, task.getSentence());
            }
            
            addNewTask(task, "Derived");
            
        } else {            
            if (recorder.isActive()) {
                recorder.onTaskRemove(task, "Ignored");
            }            
        }
    }

    /* --------------- new task building --------------- */
    /**
     * Shared final operations by all double-premise rules, called from the
     * rules except StructuralRules
     *
     * @param newContent The content of the sentence in task
     * @param newTruth The truth value of the sentence in task
     * @param newBudget The budget value in task
     */
    public void doublePremiseTaskRevised(Term newContent, TruthValue newTruth, BudgetValue newBudget) {
        if (newContent != null) {
            Sentence newSentence = new Sentence(newContent, getCurrentTask().getSentence().punctuation, newTruth, getNewStamp());
            Task newTask = new Task(newSentence, newBudget, getCurrentTask(), getCurrentBelief());
            derivedTask(newTask, true, false);
        }
    }

    /**
     * Shared final operations by all double-premise rules, called from the
     * rules except StructuralRules
     *
     * @param newContent The content of the sentence in task
     * @param newTruth The truth value of the sentence in task
     * @param newBudget The budget value in task
     */
    public void doublePremiseTask(final Term newContent, final TruthValue newTruth, final BudgetValue newBudget) {
        if (newContent != null) {
            final Sentence newSentence = new Sentence(newContent, getCurrentTask().getSentence().punctuation, newTruth, getNewStamp());
            final Task newTask = new Task(newSentence, newBudget, getCurrentTask(), getCurrentBelief());
            derivedTask(newTask, false, false);
        }
    }

    /**
     * Shared final operations by all double-premise rules, called from the
     * rules except StructuralRules
     *
     * @param newContent The content of the sentence in task
     * @param newTruth The truth value of the sentence in task
     * @param newBudget The budget value in task
     * @param revisible Whether the sentence is revisible
     */
//    public void doublePremiseTask(Term newContent, TruthValue newTruth, BudgetValue newBudget, boolean revisible) {
//        if (newContent != null) {
//            Sentence taskSentence = currentTask.getSentence();
//            Sentence newSentence = new Sentence(newContent, taskSentence.getPunctuation(), newTruth, newStamp, revisible);
//            Task newTask = new Task(newSentence, newBudget, currentTask, currentBelief);
//            derivedTask(newTask, false, false);
//        }
//    }

    /**
     * Shared final operations by all single-premise rules, called in
     * StructuralRules
     *
     * @param newContent The content of the sentence in task
     * @param newTruth The truth value of the sentence in task
     * @param newBudget The budget value in task
     */
    public void singlePremiseTask(Term newContent, TruthValue newTruth, BudgetValue newBudget) {
        singlePremiseTask(newContent, getCurrentTask().getSentence().punctuation, newTruth, newBudget);
    }

    /**
     * Shared final operations by all single-premise rules, called in
     * StructuralRules
     *
     * @param newContent The content of the sentence in task
     * @param punctuation The punctuation of the sentence in task
     * @param newTruth The truth value of the sentence in task
     * @param newBudget The budget value in task
     */
    public void singlePremiseTask(Term newContent, char punctuation, TruthValue newTruth, BudgetValue newBudget) {
        Task parentTask = getCurrentTask().getParentTask();
        if (parentTask != null && newContent.equals(parentTask.getContent())) { // circular structural inference
            return;
        }
        Sentence taskSentence = getCurrentTask().getSentence();
        if (taskSentence.isJudgment() || getCurrentBelief() == null) {
            setNewStamp(new Stamp(taskSentence.stamp, getTime()));
        } else {    // to answer a question with negation in NAL-5 --- move to activated task?
            setNewStamp(new Stamp(getCurrentBelief().stamp, getTime()));
        }

        Sentence newSentence = new Sentence(newContent, punctuation, newTruth, getNewStamp());
        Task newTask = new Task(newSentence, newBudget, getCurrentTask(), null);
        derivedTask(newTask, false, true);
    }

    public void singlePremiseTask(Sentence newSentence, BudgetValue newBudget) {
        Task newTask = new Task(newSentence, newBudget, getCurrentTask(), null);
        derivedTask(newTask, false, true);
    }

    /* ---------- system working workCycle ---------- */
    /**
     * An atomic working cycle of the system: process new Tasks, then fire a
     * concept
     * <p>
     * Called from Reasoner.tick only
     *
     * @param clock The current time to be displayed
     */
    public void workCycle(final long clock) {
        if (recorder.isActive()) {            
            recorder.preCycle(clock);
        }
        
        processNewTask();
        
        if (noResult()) {       // necessary?
            processNovelTask();
        }
        
        if (noResult()) {       // necessary?
            processConcept();
        }
        
        novelTasks.refresh();
        
        if (recorder.isActive()) {            
            recorder.postCycle(clock);
        }        
    }

    /**
     * Process the newTasks accumulated in the previous workCycle, accept
     * addInput ones and those that corresponding to existing concepts, plus one
     * from the buffer.
     */
    private void processNewTask() {
                
        // don't include new tasks produced in the current workCycle
        int counter = newTasks.size();
        Task newEvent = null;
        while (counter-- > 0) {
            final Task task = newTasks.removeFirst();
            if (task.isInput() || (termToConcept(task.getContent()) != null)) {
                // new addInput or existing concept
                immediateProcess(task);
                if (task.getSentence().stamp.getOccurrenceTime() != Stamp.ETERNAL) {
                    if ((newEvent == null)
                            || (BudgetFunctions.rankBelief(newEvent.getSentence())
                            < BudgetFunctions.rankBelief(task.getSentence()))) {
                        newEvent = task;
                    }
                }
            } else {
                final Sentence s = task.getSentence();
                if (s.isJudgment()) {
                    final double exp = s.truth.getExpectation();
                    if (exp > Parameters.DEFAULT_CREATION_EXPECTATION) {
                        // new concept formation
                        novelTasks.putIn(task);
                    } else {
                        if (recorder.isActive()) {
                            recorder.onTaskRemove(task, "Neglected");
                        }
                    }
                }
            }
        }
        if (newEvent != null) {
            if (lastEvent != null) {
                setNewStamp(Stamp.make(newEvent.getSentence().stamp, lastEvent.getSentence().stamp, getTime()));
                if (getNewStamp() != null) {
                    setCurrentTask(newEvent);
                    setCurrentBelief(lastEvent.getSentence());
                    TemporalRules.temporalInduction(newEvent.getSentence(), getCurrentBelief(), this);
                }
            }
            lastEvent = newEvent;
        }
    }

    /**
     * Select a novel task to process.
     */
    private void processNovelTask() {
        final Task task = novelTasks.takeOut();       // select a task from novelTasks
        if (task != null) {
            immediateProcess(task);
        }
    }

    /**
     * Select a concept to fire.
     */
    private void processConcept() {
        currentConcept = concepts.takeOut();
        if (getCurrentConcept() != null) {
            setCurrentTerm(getCurrentConcept().term);
            
            if (recorder.isActive()) {
                recorder.append("Concept Selected: " + getCurrentTerm() + "\n");
            }
            
            concepts.putBack(getCurrentConcept());   // current Concept remains in the bag all the time
            
            getCurrentConcept().fire();              // a working workCycle
        }
    }

    /* ---------- task processing ---------- */
    /**
     * Immediate processing of a new task, in constant time Local processing, in
     * one concept only
     *
     * @param task the task to be accepted
     */
    private void immediateProcess(final Task task) {
        setCurrentTask(task); // one of the two places where this variable is set
        
        if (recorder.isActive()) {
            recorder.append("Task Immediately Processed: " + task + "\n");
        }
        
        setCurrentTerm(task.getContent());
        currentConcept = getConcept(getCurrentTerm());
        
        if (getCurrentConcept() != null) {
            activateConcept(getCurrentConcept(), task.budget);
            getCurrentConcept().directProcess(task);
        }
    }

    /* ---------- display ---------- */
    /**
     * Start display active concepts on given bagObserver, called from
     * MainWindow.
     *
     * we don't want to expose fields concepts and novelTasks, AND we want to
     * separate GUI and inference, so this method takes as argument a
     * {@link BagObserver} and calls
     * {@link ConceptBag#addBagObserver(BagObserver, String)} ;
     *
     * see design for {@link Bag} and {@link nars.gui.BagWindow} in
     * {@link Bag#addBagObserver(BagObserver, String)}
     *
     * @param bagObserver bag Observer that will receive notifications
     * @param title the window title
     */
    public void conceptsStartPlay(final BagObserver<Concept> bagObserver, final String title) {
        bagObserver.setBag(concepts);
        concepts.addBagObserver(bagObserver, title);
    }

    /**
     * Display new tasks, called from MainWindow. see
     * {@link #conceptsStartPlay(BagObserver, String)}
     *
     * @param bagObserver
     * @param s the window title
     */
    public void taskBuffersStartPlay(final BagObserver<Task> bagObserver, final String s) {
        bagObserver.setBag(novelTasks);
        novelTasks.addBagObserver(bagObserver, s);
    }



    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer(1024);
        sb.append(toStringLongIfNotNull(concepts, "concepts"))
                .append(toStringLongIfNotNull(novelTasks, "novelTasks"))
                .append(toStringIfNotNull(newTasks, "newTasks"))
                .append(toStringLongIfNotNull(getCurrentTask(), "currentTask"))
                .append(toStringLongIfNotNull(getCurrentBeliefLink(), "currentBeliefLink"))
                .append(toStringIfNotNull(getCurrentBelief(), "currentBelief"));
        return sb.toString();
    }

    private String toStringLongIfNotNull(Bag<?> item, String title) {
        return item == null ? "" : "\n " + title + ":\n"
                + item.toStringLong();
    }

    private String toStringLongIfNotNull(Item item, String title) {
        return item == null ? "" : "\n " + title + ":\n"
                + item.toStringLong();
    }

    private String toStringIfNotNull(Object item, String title) {
        return item == null ? "" : "\n " + title + ":\n"
                + item.toString();
    }

    public AtomicInteger getTaskForgettingRate() {
        return taskForgettingRate;
    }

    public AtomicInteger getBeliefForgettingRate() {
        return beliefForgettingRate;
    }

    public AtomicInteger getConceptForgettingRate() {
        return conceptForgettingRate;
    }

    private void onTaskAdd(Task t) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    /** returns a collection of all concepts */
    public Collection<Concept> getConcepts() {
        return concepts.nameTable.values();
    }

    /**
     * @return the currentTask
     */
    public Task getCurrentTask() {
        return currentTask;
    }

    /**
     * @param currentTask the currentTask to set
     */
    public void setCurrentTask(Task currentTask) {
        this.currentTask = currentTask;
    }

    /**
     * @return the newStamp
     */
    public Stamp getNewStamp() {
        return newStamp;
    }

    /**
     * @param newStamp the newStamp to set
     */
    public void setNewStamp(Stamp newStamp) {
        this.newStamp = newStamp;
    }

    /**
     * @return the currentBelief
     */
    public Sentence getCurrentBelief() {
        return currentBelief;
    }

    /**
     * @param currentBelief the currentBelief to set
     */
    public void setCurrentBelief(Sentence currentBelief) {
        this.currentBelief = currentBelief;
    }

    /**
     * @return the currentBeliefLink
     */
    public TermLink getCurrentBeliefLink() {
        return currentBeliefLink;
    }

    /**
     * @param currentBeliefLink the currentBeliefLink to set
     */
    public void setCurrentBeliefLink(TermLink currentBeliefLink) {
        this.currentBeliefLink = currentBeliefLink;
    }

    /**
     * @return the currentTaskLink
     */
    public TaskLink getCurrentTaskLink() {
        return currentTaskLink;
    }

    /**
     * @param currentTaskLink the currentTaskLink to set
     */
    public void setCurrentTaskLink(TaskLink currentTaskLink) {
        this.currentTaskLink = currentTaskLink;
    }

    /**
     * @return the currentTerm
     */
    public Term getCurrentTerm() {
        return currentTerm;
    }

    /**
     * @param currentTerm the currentTerm to set
     */
    public void setCurrentTerm(Term currentTerm) {
        this.currentTerm = currentTerm;
    }

    /**
     * @return the currentConcept
     */
    public Concept getCurrentConcept() {
        return currentConcept;
    }

    public static final class NullInferenceRecorder implements InferenceRecorder {

        public static final NullInferenceRecorder global = new NullInferenceRecorder();

        
        private NullInferenceRecorder() {        }
        
        @Override public boolean isActive() { return false;  }

        @Override public void init() {        }

        @Override public void show() {        }

        @Override public void play() {        }

        @Override public void stop() {        }

        @Override public void append(String s) {        }

        @Override public void preCycle(long clock) {        }
        @Override public void postCycle(long clock) {        }
                
        @Override public void onConceptNew(Concept concept) {        }

        @Override public void onTaskAdd(Task task, String reason) {        }        
        @Override public void onTaskRemove(Task task, String reason) {        }
        
               
    }
    
}

// Copyright (c) 2015-2018 K Team. All Rights Reserved.
package org.kframework.compile;

import org.kframework.attributes.Att;
import org.kframework.builtin.BooleanUtils;
import org.kframework.builtin.KLabels;
import org.kframework.builtin.Sorts;
import org.kframework.definition.Context;
import org.kframework.definition.Definition;
import org.kframework.definition.Module;
import org.kframework.definition.NonTerminal;
import org.kframework.definition.Production;
import org.kframework.definition.ProductionItem;
import org.kframework.definition.Rule;
import org.kframework.definition.Sentence;
import org.kframework.kore.K;
import org.kframework.kore.KApply;
import org.kframework.kore.KLabel;
import org.kframework.kore.KToken;
import org.kframework.kore.KVariable;
import org.kframework.kore.Sort;
import org.kframework.kore.TransformK;
import org.kframework.kore.VisitK;
import org.kframework.parser.concrete2kore.ParseInModule;
import org.kframework.parser.concrete2kore.generator.RuleGrammarGenerator;
import org.kframework.utils.StringUtil;
import org.kframework.utils.errorsystem.KEMException;
import scala.collection.Set;
import scala.collection.JavaConverters;
import scala.Option;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.kframework.Collections.*;
import static org.kframework.definition.Constructors.*;
import static org.kframework.kore.KORE.*;

public class ResolveFreshConstants {

    private final Definition def;
    private final boolean kore;
    private Module m;
    private java.util.Set<KVariable> freshVars = new HashSet<>();
    private Map<KVariable, Integer> offsets = new HashMap<>();

    private void reset() {
        freshVars.clear();
        offsets.clear();
    }

    private Rule resolve(Rule rule) {
        reset();
        analyze(rule.body());
        analyze(rule.requires());
        analyze(rule.ensures());
        finishAnalysis();
        if (kore) {
            Rule withFresh = Rule(
                    addFreshCell(transform(rule.body())),
                    transform(rule.requires()),
                    transform(rule.ensures()),
                    rule.att());
            if (rule.att().contains("initializer")) {
                K left = RewriteToTop.toLeft(withFresh.body());
                if (left instanceof KApply) {
                    KApply kapp = (KApply) left;
                    if (kapp.klabel().equals(KLabels.INIT_GENERATED_TOP_CELL)) {
                        KApply right = (KApply)RewriteToTop.toRight(withFresh.body());
                        KApply cells = (KApply)right.items().get(1);
                        List<K> items = new ArrayList<>(cells.items());
                        items.add(KApply(KLabels.INIT_GENERATED_COUNTER_CELL));
                        KApply newCells = KApply(cells.klabel(), immutable(items));
                        List<K> rightItems = new ArrayList<>(right.items());
                        rightItems.set(1, newCells);
                        return Rule(
                                KRewrite(left, KApply(right.klabel(), immutable(rightItems))),
                                withFresh.requires(),
                                withFresh.ensures(),
                                withFresh.att());
                    }
                }
            }
            return withFresh;
        }
        return Rule(
                rule.body(),
                addSideCondition(rule.requires()),
                rule.ensures(),
                rule.att());
    }

    private void analyze(K term) {
        new VisitK() {
            @Override
            public void apply(KVariable k) {
                if (k.name().startsWith("!")) {
                    freshVars.add(k);
                }
                super.apply(k);
            }
        }.apply(term);
    }

    private void finishAnalysis() {
        int i = 0;
        for (KVariable v : freshVars) {
            offsets.put(v, i++);
        }
    }

    private K addSideCondition(K requires) {
        Optional<KApply> sideCondition = freshVars.stream().map(k -> {
            Optional<Sort> s = k.att().getOptional(Sort.class);
            if (!s.isPresent()) {
                throw KEMException.compilerError("Fresh constant used without a declared sort.", k);
            }
            return KApply(KLabel("#match"), k, KApply(KLabel("#fresh"), KToken(StringUtil.enquoteKString(s.get().toString()), Sorts.String())));
        }).reduce(BooleanUtils::and);
        if (!sideCondition.isPresent()) {
            return requires;
        } else if (requires.equals(BooleanUtils.TRUE) && sideCondition.isPresent()) {
            return sideCondition.get();
        } else {
            // we order the lookup after the requires clause so that the fresh constant
            // matching side condition occurs last. This is necessary in order to
            // ensure that fresh constants in rule RHSs are consecutive
            return BooleanUtils.and(requires, sideCondition.get());
        }
    }

    private static KVariable FRESH = KVariable("!Fresh", Att.empty().add(Sort.class, Sorts.Int()));

    private K transform(K term) {
        return new TransformK() {
            @Override
            public K apply(KVariable k) {
                if (freshVars.contains(k)) {
                    Optional<Sort> s = k.att().getOptional(Sort.class);
                    if (!s.isPresent()) {
                        throw KEMException.compilerError("Fresh constant used without a declared sort.", k);
                    }
                    Option<KLabel> lbl = m.freshFunctionFor().get(s.get());
                    if (!lbl.isDefined()) {
                        throw KEMException.compilerError("No fresh generator defined for sort " + s, k);
                    }
                    return KApply(lbl.get(), KApply(KLabel("_+Int_"), FRESH, KToken(offsets.get(k).toString(), Sorts.Int())));
                }
                return super.apply(k);
            }
        }.apply(term);
    }

    private K addFreshCell(K body) {
        if (freshVars.size() == 0) {
            return body;
        }
        KApply cellTerm = IncompleteCellUtils.make(KLabels.GENERATED_COUNTER_CELL, false, KRewrite(FRESH, KApply(KLabel("_+Int_"), FRESH, KToken(Integer.toString(freshVars.size()), Sorts.Int()))), false);
        return KApply(KLabels.CELLS, body, cellTerm);
    }

    private Context resolve(Context context) {
        reset();
        analyze(context.body());
        analyze(context.requires());
        finishAnalysis();
        if (kore) {
            return new Context(
                    addFreshCell(transform(context.body())),
                    transform(context.requires()),
                    context.att());
        }
        return new Context(
                context.body(),
                addSideCondition(context.requires()),
                context.att());
    }

    private Production resolve(Production prod) {
        if (kore && prod.klabel().isDefined() && prod.klabel().get().equals(KLabels.GENERATED_TOP_CELL)) {
            List<ProductionItem> pis = stream(prod.items()).collect(Collectors.toCollection(ArrayList::new));
            int idx = 0;
            int i = 0;
            for (ProductionItem pi : pis) {
                if (pi instanceof NonTerminal) {
                    idx = i;
                }
                i++;
            }
            pis.add(idx, NonTerminal(Sorts.GeneratedCounterCell()));
            return Production(prod.klabel().get(), prod.sort(), immutable(pis), prod.att());
        }
        return prod;
    }

    private Sentence resolve(Sentence s) {
        if (s instanceof Rule) {
            return resolve((Rule) s);
        } else if (s instanceof Context) {
            return resolve((Context) s);
        } else if (s instanceof Production) {
            return resolve((Production) s);
        }
        return s;
    }

    public ResolveFreshConstants(Definition def, boolean kore) {
        this.def = def;
        this.kore = kore;
    }

    public Module resolve(Module m) {
        this.m = m;
        Set<Sentence> sentences = map(this::resolve, m.localSentences());
        KToken counterCellLabel = KToken("generatedCounter", Sort("#CellName"));
        KApply freshCell = KApply(KLabel("#configCell"), counterCellLabel, KApply(KLabel("#cellPropertyListTerminator")), KToken("0", Sorts.Int()), counterCellLabel);
        if (m.equals(def.mainModule()) && kore) {
            if (!m.definedKLabels().contains(KLabels.GENERATED_TOP_CELL)) {
                RuleGrammarGenerator gen = new RuleGrammarGenerator(def);
                ParseInModule mod = RuleGrammarGenerator.getCombinedGrammar(gen.getConfigGrammar(m), true);
                ConfigurationInfoFromModule configInfo = new ConfigurationInfoFromModule(m);
                Sort topCellSort = configInfo.topCell();
                KLabel topCellLabel = configInfo.getCellLabel(topCellSort);
                Production prod = m.productionsFor().apply(topCellLabel).head();
                KToken cellName = KToken(prod.att().get("cellName"), Sort("#CellName"));

                KToken topCellToken = KToken(KLabels.GENERATED_TOP_CELL_NAME, Sort("#CellName"));
                K generatedTop = KApply(KLabel("#configCell"), topCellToken, KApply(KLabel("#cellPropertyListTerminator")), KApply(KLabels.CELLS, KApply(KLabel("#externalCell"), cellName), freshCell), topCellToken);
                Set<Sentence> newSentences = GenerateSentencesFromConfigDecl.gen(generatedTop, BooleanUtils.TRUE, Att.empty(), mod.getExtensionModule(), true);
                sentences = (Set<Sentence>) sentences.$bar(newSentences);
            }
        }
        if (kore && m.localKLabels().contains(KLabels.GENERATED_TOP_CELL)) {
            RuleGrammarGenerator gen = new RuleGrammarGenerator(def);
            ParseInModule mod = RuleGrammarGenerator.getCombinedGrammar(gen.getConfigGrammar(m), true);
            Set<Sentence> newSentences = GenerateSentencesFromConfigDecl.gen(freshCell, BooleanUtils.TRUE, Att.empty(), mod.getExtensionModule(), true);
            sentences = (Set<Sentence>) sentences.$bar(newSentences);
        }
        if (sentences.equals(m.localSentences())) {
            return m;
        }
        Set<Sentence> finalSentences = JavaConverters.asScalaSet(stream(sentences).map(s -> s instanceof Production ? fixTopCellProductionFormat((Production) s) : s).collect(Collectors.toSet()));
        return new GenerateSortPredicateSyntax().gen(Module(m.name(), kore ? m.imports() : add(def.getModule("K-REFLECTION").get(), m.imports()), finalSentences, m.att()));
    }

    private Production fixTopCellProductionFormat(Production prod) {
        if (this.kore && prod.klabel().isDefined() && prod.klabel().get().equals(KLabels.GENERATED_TOP_CELL)) {
            List<Integer> cellPositions = new ArrayList<Integer>();
            int i = 1;
            for (ProductionItem p: JavaConverters.seqAsJavaList(prod.items())) {
                if (p instanceof NonTerminal) {
                    NonTerminal nt = (NonTerminal) p;
                    if (! nt.sort().name().equals("GeneratedCounterCell")) {
                        cellPositions.add(i);
                    }
                }
                i++;
            }
            StringBuilder format = new StringBuilder();
            if (cellPositions.size() == 1) {
                format.append("%").append(cellPositions.get(0));
            } else {
                format.append("%1%i");
                int j;
                for (j = 0; j < cellPositions.size(); j++) {
                    format.append("%n%").append(cellPositions.get(j));
                }
                format.append("%d%n%").append(cellPositions.get(j - 1) + 1);
            }
            return Production(prod.klabel(), prod.sort(), prod.items(), prod.att().add("format", format.toString()));
        }
        return prod;
    }
}


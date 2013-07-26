/**
 *
 * @author degtyarjov
 * @version $Id$
 */
package com.haulmont.yarg.formatters.impl;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.haulmont.yarg.exception.ReportingException;
import com.haulmont.yarg.formatters.impl.xlsx.Range;
import com.haulmont.yarg.formatters.impl.xlsx.XlsxUtils;
import com.haulmont.yarg.structure.BandData;
import com.haulmont.yarg.structure.ReportTemplate;

import java.util.*;

import com.haulmont.yarg.structure.impl.BandOrientation;
import org.apache.commons.collections.CollectionUtils;
import org.docx4j.XmlUtils;
import org.docx4j.dml.chart.*;
import org.docx4j.dml.spreadsheetdrawing.*;
import org.docx4j.dml.spreadsheetdrawing.CTDrawing;
import org.docx4j.dml.spreadsheetdrawing.CTMarker;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.io.SaveToZipFile;
import org.docx4j.openpackaging.packages.SpreadsheetMLPackage;
import org.docx4j.openpackaging.parts.DrawingML.Drawing;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.JaxbXmlPart;
import org.docx4j.openpackaging.parts.SpreadsheetML.SharedStrings;
import org.docx4j.openpackaging.parts.SpreadsheetML.WorksheetPart;
import org.docx4j.openpackaging.parts.relationships.RelationshipsPart;
import org.docx4j.relationships.Relationship;
import org.xlsx4j.jaxb.Context;
import org.xlsx4j.sml.*;


import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XlsxFormatter extends AbstractFormatter {
    public static final Pattern CELL_ADDRESS_PATTERN = Pattern.compile("([A-z]+)([0-9]+)");
    protected Document template;
    protected Document result;

    XlsxUtils templateUtils;
    XlsxUtils resultUtils;

    long currentRow = 0;

    ArrayListMultimap<Range, Range> rangeDependencies = ArrayListMultimap.create();
    LinkedHashMultimap<Range, Range> rangeVerticalIntersections = LinkedHashMultimap.create();
    Map<BandData, RangePair> bandsToRanges = new HashMap<>();

    public XlsxFormatter(BandData rootBand, ReportTemplate reportTemplate, OutputStream outputStream) {
        super(rootBand, reportTemplate, outputStream);
    }

    @Override
    public void renderDocument() {
        init();

        List<CTDefinedName> definedName = template.workbook.getDefinedNames().getDefinedName();
        for (CTDefinedName name1 : definedName) {
            for (CTDefinedName name2 : definedName) {
                if (!name1.equals(name2)) {
                    Range range1 = Range.fromFormula(name1.getValue());
                    Range range2 = Range.fromFormula(name2.getValue());
                    if (range1.firstRow >= range2.firstRow && range1.firstRow <= range2.lastRow ||
                            range1.lastRow >= range2.firstRow && range1.lastRow <= range2.lastRow ||
                            range2.firstRow >= range1.firstRow && range2.firstRow <= range1.lastRow ||
                            range2.lastRow >= range1.firstRow && range2.lastRow <= range1.lastRow
                            ) {
                        rangeVerticalIntersections.put(range1, range2);
                        rangeVerticalIntersections.put(range2, range1);
                    }
                }
            }
        }

        for (WorksheetPart worksheet : result.worksheets) {
            worksheet.getJaxbElement().getSheetData().getRow().clear();
            worksheet.getJaxbElement().getMergeCells().getMergeCell().clear();
        }
        result.workbook.getDefinedNames().getDefinedName().clear();

        for (BandData childBand : rootBand.getChildrenList()) {
            writeBand(childBand);
        }

        setupMergeRegions();
        updateCharts();

        try {
            SaveToZipFile saver = new SaveToZipFile(result.thePackage);
            saver.save(outputStream);
        } catch (Docx4JException e) {
            throw new ReportingException("An error occurred during save result document", e);
        }
    }

    private void updateCharts() {
        for (Map.Entry<Range, ChartPair> entry : result.chartSpaces.entrySet()) {
            for (Range templateRange : rangeDependencies.keySet()) {
                if (templateRange.contains(entry.getKey())) {
                    List<Range> resultRanges = rangeDependencies.get(templateRange);
                    Range firstResultRange = resultRanges.get(0);
                    int plotOffset = firstResultRange.firstRow - templateRange.firstRow;

                    CTChart chart = entry.getValue().chartSpace.getChart();
                    CTPlotArea plotArea = chart.getPlotArea();
                    List<Object> areaChartOrArea3DChartOrLineChart = plotArea.getAreaChartOrArea3DChartOrLineChart();
                    for (Object o : areaChartOrArea3DChartOrLineChart) {
                        if (o instanceof ListSer) {
                            ListSer series = (ListSer) o;
                            List<SerContent> ser = series.getSer();
                            for (SerContent ctBarSer : ser) {
                                CTAxDataSource captions = ctBarSer.getCat();
                                CTNumDataSource data = ctBarSer.getVal();

                                Range captionsRange = Range.fromFormula(captions.getStrRef().getF());
                                Range dataRange = Range.fromFormula(data.getNumRef().getF());

                                for (Range range : rangeDependencies.keySet()) {
                                    if (range.contains(captionsRange)) {
                                        List<Range> __ranges = rangeDependencies.get(range);
                                        int offset = __ranges.get(0).firstRow - dataRange.firstRow;
                                        int grow = __ranges.get(__ranges.size() - 1).firstRow - __ranges.get(0).firstRow;

                                        captionsRange = captionsRange.shiftUpDown(offset);
                                        dataRange = dataRange.shiftUpDown(offset);

                                        captionsRange = captionsRange.growUpDown(grow);
                                        dataRange = dataRange.growUpDown(grow);

                                        captions.getStrRef().setF(captionsRange.toFormula());
                                        data.getNumRef().setF(dataRange.toFormula());
                                        break;
                                    }
                                }

//                            try {
//                                Chart chartPart = null;
//
//                                chartPart = new Chart(new PartName("/xl/charts/chart1.xml"));
//                                CTChartSpace newChartSpace = XmlUtils.deepCopy(entry.getValue());
//                                chartPart.setJaxbElement(newChartSpace);
//                                result.thePackage.addTargetPart(chartPart);
//
//                            } catch (InvalidFormatException e) {
//                                e.printStackTrace();
//                            }
                            }
                        }
                    }

                    if (resultRanges.size() > 0) {
                        CTTwoCellAnchor anchor = (CTTwoCellAnchor) entry.getValue().drawing.getJaxbElement().getEGAnchor().get(0);
                        anchor.getFrom().setRow(anchor.getFrom().getRow() + plotOffset);
                        anchor.getTo().setRow(anchor.getTo().getRow() + plotOffset);
                    }
                }

            }
        }
    }

    private void setupMergeRegions() {
        for (Range templateRange : rangeDependencies.keySet()) {
            Worksheet templateSheet = templateUtils.getSheetByName(templateRange.sheet);
            Worksheet resultSheet = resultUtils.getSheetByName(templateRange.sheet);

            if (templateSheet.getMergeCells() != null) {
                if (resultSheet.getMergeCells() == null) {
                    CTMergeCells resultMergeCells = new CTMergeCells();
                    resultMergeCells.setParent(resultSheet);
                    resultSheet.setMergeCells(resultMergeCells);
                }
            }

            for (Range resultRange : rangeDependencies.get(templateRange)) {
                for (CTMergeCell templateMergeRegion : templateSheet.getMergeCells().getMergeCell()) {
                    Range mergeRange = Range.fromRange(templateRange.sheet, templateMergeRegion.getRef());
                    if (templateRange.contains(mergeRange)) {
                        int offset = resultRange.firstRow - templateRange.firstRow;
                        Range resultMergeRange = mergeRange.shiftUpDown(offset);
                        CTMergeCell resultMergeRegion = new CTMergeCell();
                        resultMergeRegion.setRef(resultMergeRange.toRange());
                        resultMergeRegion.setParent(resultSheet.getMergeCells());
                        resultSheet.getMergeCells().getMergeCell().add(resultMergeRegion);
                    }
                }
            }
        }
    }

    private void init() {
        try {
            template = initWorkbook((SpreadsheetMLPackage) SpreadsheetMLPackage.load(reportTemplate.getDocumentContent()));

            //todo think how to get rid of manual copy
//            SpreadsheetMLPackage pkg = SpreadsheetMLPackage.createPackage();
//            WorksheetPart sheet = pkg.createWorksheetPart(new PartName("/sheet1.xml"), "data", 1);

//            result = initWorkbook(pkg);
            result = initWorkbook((SpreadsheetMLPackage) SpreadsheetMLPackage.load(reportTemplate.getDocumentContent()));

            templateUtils = new XlsxUtils(template);
            resultUtils = new XlsxUtils(result);
        } catch (Exception e) {
            throw new ReportingException(String.format("An error occurred while loading template [%s]", reportTemplate.getDocumentName()), e);
        }
    }

    private void writeBand(BandData childBand) {
        if (BandOrientation.HORIZONTAL == childBand.getOrientation()) {
            writeHBand(childBand);
        } else {
            writeVBand(childBand);
        }
    }

    private void writeVBand(BandData band) {
        Range templateRange = getBandRange(band);
        Worksheet resultSheet = resultUtils.getSheetByName(templateRange.sheet);

        Row firstRow = null;
        List<Range> alreadyRenderedRanges = rangeDependencies.get(templateRange);
        List<Row> thisSheetRows = resultSheet.getSheetData().getRow();


        int previousRangesVerticalOffset = 0;
        if (alreadyRenderedRanges.size() > 0) {
            Range previousRange = alreadyRenderedRanges.get(alreadyRenderedRanges.size() - 1);
            previousRangesVerticalOffset = previousRange.firstColumn - templateRange.firstColumn + 1;
            if (thisSheetRows.size() > previousRange.firstRow - 1) {//get current row
                firstRow = thisSheetRows.get(previousRange.firstRow - 1);
            }
        } else {
            BandData parentBand = band.getParentBand();
            RangePair rangePair = bandsToRanges.get(parentBand);
            if (rangePair != null) {
                Range resultParentRange = rangePair.resultRange;
                Range templateParentRange = rangePair.templateRange;

                if (templateParentRange.firstRow == templateRange.firstRow) {
                    if (thisSheetRows.size() > resultParentRange.firstRow - 1) {//get current row
                        firstRow = thisSheetRows.get(resultParentRange.firstRow - 1);
                    }
                } else {
                    if (thisSheetRows.size() > resultParentRange.firstRow) {//get next row
                        firstRow = thisSheetRows.get(resultParentRange.firstRow);
                    }
                }
            }
        }

        if (firstRow == null) {
            firstRow = createNewRow();
            thisSheetRows.add(firstRow);

            for (int i = 0; i < templateRange.lastRow - templateRange.firstRow; i++) {
                Row row = createNewRow();
                thisSheetRows.add(row);
            }

        }

        List<Cell> resultCells = new ArrayList<>();

        for (int i = 0; i <= templateRange.lastRow - templateRange.firstRow; i++) {
            Range oneRowRange = new Range(templateRange.sheet, templateRange.firstColumn, templateRange.firstRow + i, templateRange.lastColumn, templateRange.firstRow + i );
            List<Cell> templateCells = templateUtils.getCellsByRange(oneRowRange);
            Row currentRow = thisSheetRows.get((int) (firstRow.getR() + i - 1));
            List<Cell> currentRowResultCells = copyCells(band, currentRow, templateCells);
            for (Cell resultCell : currentRowResultCells) {
                Matcher matcher = CELL_ADDRESS_PATTERN.matcher(resultCell.getR());
                if (matcher.find()) {
                    String column = matcher.group(1);
                    String row = matcher.group(2);
                    String newColumn = XlsxUtils.getColumnReferenceFromNumber(XlsxUtils.getNumberFromColumnReference(column) + previousRangesVerticalOffset);
                    resultCell.setR(newColumn + row);
                    resultCells.add(resultCell);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(resultCells)) {
            Range resultRange = Range.fromCells(templateRange.sheet, resultCells.get(0).getR(), resultCells.get(resultCells.size() - 1).getR());
            rangeDependencies.put(templateRange, resultRange);
            bandsToRanges.put(band, new RangePair(templateRange, resultRange));
        }
    }

    private void writeHBand(BandData band) {
        Range templateRange = getBandRange(band);
        Worksheet resultSheet = resultUtils.getSheetByName(templateRange.sheet);

        Row newRow = null;
        List<Range> alreadyRenderedRanges = rangeDependencies.get(templateRange);
        List<Row> thisSheetRows = resultSheet.getSheetData().getRow();

        if (alreadyRenderedRanges.size() > 0) {
            Range previousRange = alreadyRenderedRanges.get(alreadyRenderedRanges.size() - 1);
            if (thisSheetRows.size() > previousRange.firstRow) {//get next row
                newRow = thisSheetRows.get(previousRange.firstRow);
            }
        } else {
            Collection<Range> templateNeighbours = rangeVerticalIntersections.get(templateRange);
            for (Range templateNeighbour : templateNeighbours) {
                Collection<Range> resultRanges = rangeDependencies.get(templateNeighbour);
                if (resultRanges.size() > 0) {
                    Range firstResultRange = resultRanges.iterator().next();
                    newRow = thisSheetRows.get(firstResultRange.firstRow - 1);
                    break;
                }
            }
        }

        if (newRow == null) {
            newRow = createNewRow();
            thisSheetRows.add(newRow);
        }

        List<Cell> templateCells = templateUtils.getCellsByRange(templateRange);
        List<Cell> resultCells = copyCells(band, newRow, templateCells);

        if (CollectionUtils.isNotEmpty(resultCells)) {
            Range resultRange = Range.fromCells(templateRange.sheet, resultCells.get(0).getR(), resultCells.get(resultCells.size() - 1).getR());
            rangeDependencies.put(templateRange, resultRange);
            bandsToRanges.put(band, new RangePair(templateRange, resultRange));

            for (BandData child : band.getChildrenList()) {
                writeBand(child);
            }
        }
    }


    private Range getBandRange(BandData childBand) {
        CTDefinedName targetRange = templateUtils.getDefinedName(childBand.getName());
        return Range.fromFormula(targetRange.getValue());
    }

    private Row createNewRow() {
        Row newRow = Context.getsmlObjectFactory().createRow();
        currentRow++;
        newRow.setR(currentRow);
        return newRow;
    }

    private List<Cell> copyCells(BandData childBand, Row newRow, List<Cell> templateCells) {
        List<Cell> resultCells = new ArrayList<>();
        for (Cell templateCell : templateCells) {
            Cell newCell = XmlUtils.deepCopy(templateCell, Context.jcSML);
            resultCells.add(newCell);

            newCell.setR(newCell.getR().replaceAll("[0-9]+", String.valueOf(newRow.getR())));
            newRow.getC().add(newCell);
            newCell.setParent(newRow);

            String cellValue = templateUtils.getCellValue(newCell);
            if (cellValue != null) {
                String value = insertBandDataToString(childBand, cellValue);
                newCell.setV(value);
            } else {
                newCell.setV("");
            }
            newCell.setT(STCellType.N);
        }
        return resultCells;
    }

    private Document initWorkbook(SpreadsheetMLPackage thePackage) {
        Document document = new Document();
        document.thePackage = thePackage;
        RelationshipsPart rp = document.thePackage.getRelationshipsPart();
        traverse(document, null, rp);

        return document;
    }

    private void traverse(Document document, Part parent, RelationshipsPart rp) {
        for (Relationship r : rp.getRelationships().getRelationship()) {
            Part part = rp.getPart(r);
            if (document.handled.contains(part)) {
                continue;
            }

            if (part instanceof JaxbXmlPart) {
                Object o = ((JaxbXmlPart) part).getJaxbElement();

                if (o instanceof CTChartSpace) {
                    Drawing drawing = (Drawing) parent;
                    CTDrawing ctDrawing = drawing.getJaxbElement();
                    Object anchorObj = ctDrawing.getEGAnchor().get(0);

                    Range range = null;
                    if (anchorObj instanceof CTTwoCellAnchor) {
                        CTTwoCellAnchor ctTwoCellAnchor = (CTTwoCellAnchor) anchorObj;
                        CTMarker from = ctTwoCellAnchor.getFrom();
                        CTMarker to = ctTwoCellAnchor.getTo();
                        range = new Range("data", from.getCol(), from.getRow(), to.getCol(), to.getRow());
                    }

                    document.chartSpaces.put(range, new ChartPair((CTChartSpace) o, drawing));
                }

                if (o instanceof Workbook) {
                    document.workbook = (Workbook) o;
                }
            }

            if (part instanceof WorksheetPart) {
                document.worksheets.add((WorksheetPart) part);
            } else if (part instanceof SharedStrings) {
                document.sharedStrings = (SharedStrings) part;
            }

            document.handled.add(part);

            if (part.getRelationshipsPart() != null) {
                traverse(document, part, part.getRelationshipsPart());
            }
        }
    }

    public static class Document {
        public SpreadsheetMLPackage thePackage;
        public List<WorksheetPart> worksheets = new ArrayList<>();
        public Map<Range, ChartPair> chartSpaces = new HashMap<>();
        public Workbook workbook;
        public SharedStrings sharedStrings;
        private HashSet<Part> handled = new HashSet<Part>();
    }

    public static class RangePair {
        private final Range templateRange;
        private final Range resultRange;

        public RangePair(Range templateRange, Range resultRange) {
            this.templateRange = templateRange;
            this.resultRange = resultRange;
        }
    }

    public static class ChartPair {
        private final CTChartSpace chartSpace;
        private final Drawing drawing;

        public ChartPair(CTChartSpace chartSpace, Drawing drawing) {
            this.chartSpace = chartSpace;
            this.drawing = drawing;
        }
    }
}

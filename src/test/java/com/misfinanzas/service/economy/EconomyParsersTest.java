package com.misfinanzas.service.economy;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** The three sources' JSON, trimmed from what they really answered on 2026-09-26. */
class EconomyParsersTest {

    @Test
    void ineTableGivesEachSeriesItsNamePartsAndPointsOldestFirst() {
        // DATOS_TABLA lists the newest point first; Fecha is epoch millis there
        String json = """
                [{"COD":"IPC294107","Nombre":"Cataluña. Índice general. Variación anual. ","FK_Unidad":135,"Data":[
                  {"Fecha":1785535200000,"FK_TipoDato":1,"FK_Periodo":8,"Anyo":2026,"Valor":4.0,"Secreto":false},
                  {"Fecha":1782856800000,"FK_TipoDato":1,"FK_Periodo":7,"Anyo":2026,"Valor":3.3,"Secreto":false}]},
                 {"COD":"IPV1","Nombre":"Nacional. Vivienda segunda mano. Variación anual. ","Data":[
                  {"FK_Periodo":20,"Anyo":2026,"Valor":10.6},{"FK_Periodo":19,"Anyo":2026,"Valor":null}]}]
                """;

        List<IneParser.Series> series = IneParser.parse(json);

        assertThat(series.get(0).parts()).containsExactly("Cataluña", "Índice general", "Variación anual");
        assertThat(series.get(0).code()).isEqualTo("IPC294107");
        assertThat(series.get(0).points()).extracting(IneParser.Point::period, IneParser.Point::value)
                .containsExactly(tuple("2026-07", 3.3), tuple("2026-08", 4.0));
        // quarters; a point without a value (not published) is left out
        assertThat(series.get(1).points()).extracting(IneParser.Point::period).containsExactly("2026-Q2");
    }

    @Test
    void ineSingleSeriesIsAnObject() {
        String json = """
                {"COD":"IRAV1","Nombre":"Total Nacional. Índice general. Variación anual. ","Data":[
                  {"Fecha":1782856800000,"FK_Periodo":7,"Anyo":2026,"Valor":2.49},{"FK_Periodo":8,"Anyo":2026,"Valor":2.47}]}
                """;
        IneParser.Series irav = IneParser.parse(json).getFirst();
        assertThat(irav.last()).isEqualTo("Variación anual");
        assertThat(irav.points()).extracting(IneParser.Point::value).containsExactly(2.49, 2.47);
    }

    @Test
    void inePeriodIds() {
        assertThat(IneParser.period(2026, 1)).isEqualTo("2026-01");
        assertThat(IneParser.period(2026, 12)).isEqualTo("2026-12");
        assertThat(IneParser.period(2026, 19)).isEqualTo("2026-Q1");
        assertThat(IneParser.period(2026, 22)).isEqualTo("2026-Q4");
        assertThat(IneParser.period(2026, 28)).isNull(); // a year, not used
    }

    @Test
    void ineGarbageIsAClearError() {
        assertThatThrownBy(() -> IneParser.parse("<html>mantenimiento</html>")).isInstanceOf(EconomyFetchException.class);
        assertThatThrownBy(() -> IneParser.parse("{\"error\":1}")).isInstanceOf(EconomyFetchException.class);
    }

    @Test
    void fuelAveragesPerCommunityWithTheMinistrysOwnNumbering() {
        String json = """
                {"Fecha":"26/09/2026 17:17:45","ListaEESSPrecio":[
                  {"IDCCAA":"09","Provincia":"BARCELONA","Tipo Venta":"P","Precio Gasolina 95 E5":"1,900","Precio Gasoleo A":"1,800"},
                  {"IDCCAA":"09","Provincia":"GIRONA","Tipo Venta":"P","Precio Gasolina 95 E5":"2,000","Precio Gasoleo A":""},
                  {"IDCCAA":"09","Provincia":"LLEIDA","Tipo Venta":"R","Precio Gasolina 95 E5":"1,000","Precio Gasoleo A":"1,000"},
                  {"IDCCAA":"07","Provincia":"CUENCA","Tipo Venta":"P","Precio Gasolina 95 E5":"1,700","Precio Gasoleo A":"1,600"}]}
                """;

        Map<Region, FuelParser.Averages> averages = FuelParser.parse(json);

        // a station for restricted customers ("R") doesn't count; one without diesel doesn't pull its average
        assertThat(averages.get(Region.CT)).isEqualTo(new FuelParser.Averages(1.95, 1.8));
        // for the Ministry 07 is Castilla-La Mancha (for the INE it would be Castilla y León)
        assertThat(averages.get(Region.CM)).isEqualTo(new FuelParser.Averages(1.7, 1.6));
        assertThat(averages).doesNotContainKey(Region.CL);
        assertThat(averages.get(Region.ES)).isEqualTo(new FuelParser.Averages(1.867, 1.7));
    }

    @Test
    void fuelPrices() {
        assertThat(FuelParser.price("1,459")).isEqualTo(1.459);
        assertThat(FuelParser.price("")).isNull();
        assertThat(FuelParser.price("n/d")).isNull();
        assertThatThrownBy(() -> FuelParser.parse("{\"ListaEESSPrecio\":[]}")).isInstanceOf(EconomyFetchException.class);
    }

    @Test
    void powerReadsOnlyThePvpcInLocalTime() {
        String json = """
                {"data":{"type":"Precios mercado peninsular en tiempo real"},"included":[
                  {"type":"PVPC","attributes":{"title":"PVPC","values":[
                    {"value":214.52,"percentage":0.5,"datetime":"2026-09-26T00:00:00.000+02:00"},
                    {"value":198.57,"percentage":0.5,"datetime":"2026-09-26T01:00:00.000+02:00"}]}},
                  {"type":"Precio mercado spot","attributes":{"title":"Precio mercado spot","values":[
                    {"value":207.72,"datetime":"2026-09-26T00:00:00.000+02:00"}]}}]}
                """;

        assertThat(PowerParser.parse(json)).containsExactly(
                new PowerParser.Hour(LocalDateTime.parse("2026-09-26T00:00"), 214.52),
                new PowerParser.Hour(LocalDateTime.parse("2026-09-26T01:00"), 198.57));
    }

    @Test
    void powerRefusalSaysWhy() {
        String json = """
                {"errors":[{"status":"400","title":"Error Interno","detail":"Los datos solicitados no están disponibles en este momento."}]}
                """;
        assertThatThrownBy(() -> PowerParser.parse(json)).isInstanceOf(EconomyFetchException.class)
                .hasMessageContaining("no están disponibles");
    }

    @Test
    void bankOfSpainSeriesComeNewestFirstAndBecomeMonths() {
        String json = """
                [{"serie":"D_1NBAF472","descripcion":"Euribor a un año","codFrecuencia":"M",
                  "fechas":["2026-08-01T08:15:00Z","2026-07-01T08:15:00Z"],"valores":[2.954,2.855]}]
                """;
        assertThat(BdeParser.monthly(json)).containsExactly(new IneParser.Point("2026-07", 2.855), new IneParser.Point("2026-08", 2.954));
        assertThatThrownBy(() -> BdeParser.monthly("[{\"errNum\":412}]")).isInstanceOf(EconomyFetchException.class);
    }

    @Test
    void ecbCsvGroupsObservationsBySeriesKey() {
        String csv = """
                KEY,FREQ,REF_AREA,CURRENCY,TIME_PERIOD,OBS_VALUE,OBS_STATUS,TITLE
                FM.B.U2.EUR.4F.KR.DFR.LEV,B,U2,EUR,2026-09-16,2.5,A,"Deposit facility - date of changes, level"
                FM.B.U2.EUR.4F.KR.DFR.LEV,B,U2,EUR,2026-06-17,2.25,A,"Deposit facility - date of changes, level"
                EXR.D.USD.EUR.SP00.A,D,U2,USD,2026-09-25,1.1403,A,US dollar/Euro
                EXR.D.USD.EUR.SP00.A,D,U2,USD,2026-09-26,,M,US dollar/Euro
                """;
        Map<String, List<IneParser.Point>> series = EcbParser.parse(csv);
        assertThat(series.get("FM.B.U2.EUR.4F.KR.DFR.LEV")).extracting(IneParser.Point::period).containsExactly("2026-06-17", "2026-09-16");
        // an empty observation is skipped
        assertThat(series.get("EXR.D.USD.EUR.SP00.A")).containsExactly(new IneParser.Point("2026-09-25", 1.1403));
        assertThatThrownBy(() -> EcbParser.parse("{\"header\":{}}")).isInstanceOf(EconomyFetchException.class);
    }

    @Test
    void eurostatJsonStatIsPulledApartByCountryAndMonth() {
        // what une_rt_m answered: two countries x three months, Spain's August not published yet
        String json = """
                {"id":["freq","s_adj","age","unit","sex","geo","time"],"size":[1,1,1,1,1,2,3],
                 "dimension":{"geo":{"category":{"index":{"EU27_2020":0,"ES":1}}},
                              "time":{"category":{"index":{"2026-06":0,"2026-07":1,"2026-08":2}}}},
                 "value":{"0":6.1,"1":6.1,"2":6.0,"3":10.1,"4":10.0}}
                """;
        Map<String, List<IneParser.Point>> byGeo = EurostatParser.byGeo(json);
        assertThat(byGeo.get("ES")).containsExactly(new IneParser.Point("2026-06", 10.1), new IneParser.Point("2026-07", 10.0));
        assertThat(byGeo.get("EU27_2020")).extracting(IneParser.Point::period).containsExactly("2026-06", "2026-07", "2026-08");
        assertThatThrownBy(() -> EurostatParser.byGeo("{\"error\":[{\"label\":\"Dataset not found\"}]}"))
                .isInstanceOf(EconomyFetchException.class).hasMessageContaining("Dataset not found");
    }

    @Test
    void theRegionIsFoundWhereverTheTableNamesIt() {
        String json = """
                [{"COD":"A","Nombre":"Tasa de paro de la población. Ambos sexos. Cataluña. Total. ","Data":[]},
                 {"COD":"B","Nombre":"Viviendas. Número de hipotecas. Total Nacional. Base nueva. Mensual. ","Data":[]}]
                """;
        List<IneParser.Series> series = IneParser.parse(json);
        assertThat(series.get(0).region()).contains(Region.CT);
        assertThat(series.get(1).region()).contains(Region.ES);
    }
}

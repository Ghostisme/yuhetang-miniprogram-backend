package com.yuwatang.reservation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.yuwatang.reservation.Data.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:yht_import_guides;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","app.mode=local",
    "app.data-dir=target/isolated-import-guides","app.jwt-secret=isolated-import-guides-more-than-32-characters",
    "app.admin-password=Yht@9800dev","app.pms.enabled=false",
    "app.wechat.app-id=","app.wechat.secret=","app.wechat.service-app-id=","app.wechat.service-secret=",
    "app.sms.secret-id=","app.sms.secret-key="
})
@AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportGuidesIntegrationTest {
    @Autowired MockMvc mvc;@Autowired Data data;@Autowired AuthService auth;@Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    static final List<String> KINDS=List.of("members","therapists","stores","services","dianping-customers","dianping-orders");
    @BeforeAll void noOutbound(){scheduler.destroy();}
    String token(String id){return auth.token(id,"ADMIN",integer(data.one("SELECT token_version FROM app_user WHERE id=?",id),"tokenVersion",0));}
    byte[] download(String kind)throws Exception{
        var response=mvc.perform(get("/api/admin/import-guides/"+kind+"/example").header("Authorization","Bearer "+token("hq"))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());assertTrue(response.getContentType().contains("spreadsheetml"));
        return response.getContentAsByteArray();
    }
    @Test void everyExampleUsesImporterHeadersAndTextCellsWithInstructions()throws Exception{
        for(String kind:KINDS){
            var guide=ImportGuides.describe(kind);
            var response=mvc.perform(get("/api/admin/import-guides/"+kind).header("Authorization","Bearer "+token("hq"))).andReturn().getResponse();
            assertEquals(200,response.getStatus());var metadata=data.object(response.getContentAsString(StandardCharsets.UTF_8));assertEquals(kind,metadata.get("kind"));
            byte[] example=download(kind);
            var fixtureFolder=Path.of("target-api/import-guide-fixtures");Files.createDirectories(fixtureFolder);
            Files.writeString(fixtureFolder.resolve(kind+".json"),response.getContentAsString(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
            Files.write(fixtureFolder.resolve(kind+".xlsx"),example);
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(example))){
                assertEquals(2,workbook.getNumberOfSheets());assertEquals("资料",workbook.getSheetName(0));assertEquals("填写说明",workbook.getSheetName(1));
                var sheet=workbook.getSheetAt(0);assertEquals(guide.fields().size(),sheet.getRow(0).getPhysicalNumberOfCells());
                for(int c=0;c<guide.fields().size();c++)assertEquals(guide.fields().get(c).key(),sheet.getRow(0).getCell(c).getStringCellValue());
                for(int r=1;r<=guide.rows().size();r++)for(int c=0;c<guide.fields().size();c++){
                    var cell=sheet.getRow(r).getCell(c);assertEquals(CellType.STRING,cell.getCellType());
                    assertEquals(guide.rows().get(r-1).get(guide.fields().get(c).key()),cell.getStringCellValue());
                }
                assertNotNull(sheet.getPaneInformation());assertTrue(workbook.getSheetAt(1).getLastRowNum()>guide.fields().size());
                if(kind.equals("members"))assertEquals("00012345",sheet.getRow(1).getCell(5).getStringCellValue());
            }
        }
    }
    @Test void metadataAndDownloadsEnforceHeadquartersImportPermission()throws Exception{
        for(String suffix:List.of("","/example")){
            assertEquals(401,mvc.perform(get("/api/admin/import-guides/members"+suffix)).andReturn().getResponse().getStatus());
            assertEquals(403,mvc.perform(get("/api/admin/import-guides/members"+suffix).header("Authorization","Bearer "+token("yuyao-manager"))).andReturn().getResponse().getStatus());
            assertEquals(404,mvc.perform(get("/api/admin/import-guides/unknown"+suffix).header("Authorization","Bearer "+token("hq"))).andReturn().getResponse().getStatus());
        }
    }
    @Test void exampleDownloadAndPrecheckDoNotWriteSampleCustomers()throws Exception{
        long before=data.count("SELECT COUNT(*) FROM customer_profile");
        byte[] example=download("members");
        var response=mvc.perform(multipart("/api/admin/imports/members").file(new MockMultipartFile("file","example.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",example)).param("commit","false").header("Authorization","Bearer "+token("hq"))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());var preview=data.object(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(2,integer(preview,"total",0));assertEquals(false,preview.get("valid"),"Placeholder store IDs must be replaced");
        assertEquals(before,data.count("SELECT COUNT(*) FROM customer_profile"));
        var blank=mvc.perform(get("/api/admin/imports/members/template").header("Authorization","Bearer "+token("hq"))).andReturn().getResponse();
        assertEquals(200,blank.getStatus());try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(blank.getContentAsByteArray()))){assertEquals(0,workbook.getSheetAt(0).getLastRowNum());}
    }
}

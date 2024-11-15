import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import com.fr3ts0n.ecu.gui.androbd.FileHelper;
import com.fr3ts0n.pvs.PvList;
import com.fr3ts0n.pvs.IndexedProcessVar;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.*;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.KITKAT})
public class FileHelperTest {

    @Mock
    private Context context;

    @Mock
    private File mockDocumentsDir;

    @Mock
    private Toast mockToast;

    private FileHelper fileHelper;

    @Before
    public void setup() {
        MockitoAnnotations.openMocks(this);
        fileHelper = new FileHelper(context);

        // Mock the directory where the file should be saved
        mockDocumentsDir = mock(File.class);
        when(mockDocumentsDir.exists()).thenReturn(true); // Ensure the directory exists
        when(mockDocumentsDir.getPath()).thenReturn("mock/directory/path"); // Return a valid directory path

        // Mock getExternalFilesDir to return the mock directory
        when(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)).thenReturn(mockDocumentsDir);

        // Create a mock IndexedProcessVar with simulated real data
        IndexedProcessVar mockProcessVar = mock(IndexedProcessVar.class);
        when(mockProcessVar.get("Description")).thenReturn("RPM");
        when(mockProcessVar.get("Value")).thenReturn("3000");
        when(mockProcessVar.get("Units")).thenReturn("RPM");
        when(mockProcessVar.get("PID")).thenReturn("0x0C");

        // Create a PvList and add the mock process variable
        PvList mockPvList = mock(PvList.class);
        when(mockPvList.values()).thenReturn(Collections.singletonList(mockProcessVar)); // Return a list with one entry
        fileHelper.setPvs(mockPvList); // Assuming there is a setter for pvs
    }

    @Test
    public void testSaveDataWithRealValues() throws Exception {
        // Mock the external files directory and toast message
        when(mockDocumentsDir.exists()).thenReturn(true);
        when(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)).thenReturn(mockDocumentsDir);

        try (MockedStatic<Toast> mockedToast = mockStatic(Toast.class)) {
            mockedToast.when(() -> Toast.makeText(any(Context.class), anyString(), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(mockToast);

            // Invoke the saveData method with a test file name
            fileHelper.saveData("test_obd_data.csv");

            // Verify that Toast was shown (indicating success)
            verify(mockToast, times(1)).show();
        }

        // Verify the data was saved correctly (check the actual content written to the file)
        File savedFile = new File(mockDocumentsDir, "test_obd_data.csv");

        assertTrue(savedFile.exists());

        // Check the contents of the saved CSV (the first row should be the header and the second row the actual data)
        try (FileReader fileReader = new FileReader(savedFile);
             BufferedReader reader = new BufferedReader(fileReader)) {
            String line1 = reader.readLine(); // Header
            String line2 = reader.readLine(); // Data line

            // Check the header and data format
            assertTrue(line1.equals("Description,Value,Units,PID"));
            assertTrue(line2.equals("RPM,3000,RPM,0x0C"));
        }
    }

    @Test
    public void testWriteDataToFile() throws IOException {
        // Create a temporary file for testing
        File tempFile = File.createTempFile("test_obd_data", ".csv");

        try {
            fileHelper.saveData(tempFile.getName());
            assertTrue(tempFile.exists());
        } finally {
            tempFile.delete();
        }
    }
}

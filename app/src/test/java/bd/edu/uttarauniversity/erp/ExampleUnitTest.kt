package bd.edu.uttarauniversity.erp

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for the ERP application components.
 * 
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ERPComponentTest {
    
    @Test
    fun webViewState_loading_isCorrect() {
        val loadingState = WebViewState.Loading
        assertTrue("Loading state should be instance of WebViewState.Loading", 
                  loadingState is WebViewState.Loading)
    }
    
    @Test
    fun webViewState_success_hasCorrectDefaultMessage() {
        val successState = WebViewState.Success()
        assertTrue("Success state should show message by default", 
                  successState.showSuccessMessage)
    }
    
    @Test
    fun webViewState_error_hasCorrectDefaultType() {
        val errorState = WebViewState.Error()
        assertEquals("Error state should default to NETWORK type", 
                    ErrorType.NETWORK, errorState.errorType)
    }
    
    @Test
    fun errorInfo_networkError_hasCorrectContent() {
        val errorInfo = ErrorInfo(
            title = "Connection Failed",
            message = "Unable to connect to the ERP server.\nPlease check your internet connection and try again.",
            icon = androidx.compose.material.icons.Icons.Default.SignalWifiOff,
            tip = "💡 Tip: Make sure you have a stable internet connection"
        )
        
        assertEquals("Connection Failed", errorInfo.title)
        assertTrue("Message should contain connection guidance", 
                  errorInfo.message.contains("Unable to connect"))
        assertTrue("Tip should provide helpful guidance", 
                  errorInfo.tip.contains("stable internet connection"))
    }
    
    @Test
    fun errorType_allTypes_areDefined() {
        val allTypes = ErrorType.values()
        assertEquals("Should have 4 error types", 4, allTypes.size)
        assertTrue("Should contain NETWORK type", allTypes.contains(ErrorType.NETWORK))
        assertTrue("Should contain SSL type", allTypes.contains(ErrorType.SSL))
        assertTrue("Should contain TIMEOUT type", allTypes.contains(ErrorType.TIMEOUT))
        assertTrue("Should contain UNKNOWN type", allTypes.contains(ErrorType.UNKNOWN))
    }
    
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}
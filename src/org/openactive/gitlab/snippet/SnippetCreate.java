package org.openactive.gitlab.snippet;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.TrustStrategy;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

public class SnippetCreate extends AnAction implements Configurable
{
   private JTextField urlField, tokenField;


   @Nls
   @Override
   public String getDisplayName()
   {
      return "Gitlab Snippets";
   }


   private boolean isConfigured()
   {
      return StringUtils.isNotBlank( getToken() ) &&
        StringUtils.isNotBlank( getUrl() );
   }

   private String getToken()
   {
      PropertiesComponent props = PropertiesComponent.getInstance();
      String token = props.getValue( "org.openactive.gitlab.snippets.token" );
      if ( token == null ) token = "";
      return token;
   }

   private String getUrl()
   {
      PropertiesComponent props = PropertiesComponent.getInstance();
      String url = props.getValue( "org.openactive.gitlab.snippets.url" );
      if ( url == null ) url = "";
      return url;
   }

   private String getUseableUrl()
   {
      String url = getUrl();
      if ( url != null )
      {
         if ( url.endsWith( "api/v4/snippets" ) || url.endsWith( "api/v4/snippets/" ) ) return url;
         else if ( url.endsWith( "/" ) ) return url + "api/v4/snippets";
         else return url + "/api/v4/snippets";
      }
      return "";
   }

   @Nullable
   @Override
   public JComponent createComponent()
   {
      JLabel urlHint = new JLabel("This url should point at the root of your Gitlab install.\n i.e. https://gitlab.company.com" );
      JLabel urlLable = new JLabel( "Gitlab Install URL" );
      urlField = new JTextField( getUrl() );

      JLabel tokenLable = new JLabel( "Token" );
      tokenField = new JTextField( getToken() );

      JPanel panel = new JPanel( new GridBagLayout() );
      Bag bag = new Bag();


      panel.add( urlLable, bag );
      panel.add( urlField, bag.nextX().fillX() );
      panel.add( urlHint, bag.nextY().fillNone());

      JPanel spacer = new JPanel();
      spacer.setPreferredSize( new Dimension( 5, 30 ) );
      panel.add( spacer, bag.nextY().resetX() );

      panel.add( tokenLable, bag.nextY().resetX().fillNone().colspan( 1 ) );
      panel.add( tokenField, bag.nextX().fillX() );

      JLabel tokenHint = new JLabel( "To create a new token https://gitlab.company.com/profile/personal_access_tokens" );
      panel.add( tokenHint, bag.nextY().fillNone() );

      panel.add( Bag.spacer(), bag.nextY().resetX().colspan( 2 ).fillBoth() );

      return panel;
   }

   @Override
   public boolean isModified()
   {
      if ( urlField.getText().trim().length() > 0 && tokenField.getText().trim().length() > 0 )
      {
         return !(urlField.getText().matches( getUrl() ) &&
           tokenField.getText().matches( getToken() ));
      }
      return false;
   }

   @Override
   public void apply() throws ConfigurationException
   {
      PropertiesComponent props = PropertiesComponent.getInstance();
      props.setValue( "org.openactive.gitlab.snippets.url", urlField.getText().trim() );
      props.setValue( "org.openactive.gitlab.snippets.token", tokenField.getText().trim() );
   }

   private String post( String text, String fileName, VirtualFile[] files) throws Exception
   {
      CloseableHttpResponse resp = null;

      HttpPost post = new HttpPost( getUseableUrl() );
      post.addHeader( "PRIVATE-TOKEN", getToken() );

      String data = getData( fileName, text, fileName, files );
      StringEntity ent = new StringEntity( data, ContentType.APPLICATION_JSON );
      post.setEntity( ent );

      SSLContextBuilder builderssl = new SSLContextBuilder();
      builderssl.loadTrustMaterial(null, new TrustStrategy() {
         @Override
         public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            return true;
         }
      });
      SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(builderssl.build(),
              SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);


      try ( CloseableHttpClient client = HttpClients.custom().setSSLSocketFactory(sslsf).build() )
      {
         resp = client.execute( post );
         byte[] buff = new byte[1024];
         int read = 0;
         InputStream is = resp.getEntity().getContent();
         StringBuilder builder = new StringBuilder();
         while ( (read = is.read( buff )) != -1 )
         {
            builder.append( new String( buff, 0, read ) );
         }
         JSONObject obj = new JSONObject( builder.toString() );
         String snippetUrl = obj.getString( "web_url" );

         StringSelection stringSelection = new StringSelection( snippetUrl );
         Clipboard clpbrd = Toolkit.getDefaultToolkit().getSystemClipboard();
         clpbrd.setContents( stringSelection, null );
         return snippetUrl;
      }
      finally
      {
         try { resp.close(); } catch ( Exception e ) {}
      }
   }

   private String getData( String title, String content, String fileName, VirtualFile[] files )
   {
      Map<String, String> data = new HashMap<>();
      data.put( "title", title );
      data.put( "content", content );
      data.put( "file_name", fileName );
      data.put( "visibility", "internal" );

      if (files.length > 0 && content == null) {
         data.put( "title", "Code Snippet Multiple Files" );
         data.put( "content", "" );
         data.put( "file_name", "Code Snippet Multiple Files" );
         data.put( "visibility", "internal" );
         for (VirtualFile file : files) {
            data.put( "content",
                    data.get("content") + "\n\n--------"+  file.getName() +"------------\n\n" +LoadTextUtil.loadText(file));
         }
      }

      return new JSONObject( data ).toString();
   }

   @Override
   public void update( AnActionEvent e )
   {
      // only show this action if some text is selected
      Editor editor = FileEditorManager.getInstance( e.getProject() ).getSelectedTextEditor();
      VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
      String s = editor.getCaretModel().getCurrentCaret().getSelectedText();

      if ( s == null || s.length() == 0 || !isConfigured() )
      {
            e.getPresentation().setVisible( false );
      }

      if (files != null && files.length > 0)
      {
         e.getPresentation().setVisible( true );
      }
   }

   @Override
   public void actionPerformed( AnActionEvent e )
   {
      try
      {
         Editor editor = FileEditorManager.getInstance( e.getProject() ).getSelectedTextEditor();
         VirtualFile vf = e.getData( PlatformDataKeys.VIRTUAL_FILE );
         String s = editor.getCaretModel().getCurrentCaret().getSelectedText();
         VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

         String url = post( s, vf != null ? vf.getName() : "unknown", files);

         Notification note = new Notification(
           "Gitlab Snippet",
           IconLoader.getIcon( "/gl.png" ),
           "Gitlab Snippet Created",
           null,
           "New snippet at <b>" + url + "</b> has been copied to the clipboard.",
           NotificationType.INFORMATION,
           null
         );

         Notifications.Bus.notify( note );
      }
      catch ( Exception ex )
      {
         ex.printStackTrace();
         Notification note = new Notification(
           "Gitlab Snippet",
           IconLoader.getIcon( "/gl.png" ),
           "Failed creating Gitlab Snippet",
           null,
           ex.getMessage(),
           NotificationType.ERROR,
           null
         );

         Notifications.Bus.notify( note );
      }
   }
}

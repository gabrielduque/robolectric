package org.robolectric.shadows;

import android.content.res.*;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Display;
import org.jetbrains.annotations.NotNull;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.internal.HiddenApi;
import org.robolectric.internal.ReflectionHelpers;
import org.robolectric.res.*;
import org.robolectric.res.builder.XmlFileBuilder;
import org.robolectric.util.Util;
import org.w3c.dom.Document;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.robolectric.Robolectric.directlyOn;
import static org.robolectric.Robolectric.shadowOf;

/**
 * Shadow of {@code Resources} that simulates the loading of resources
 *
 * @see org.robolectric.RobolectricTestRunner#RobolectricTestRunner(Class)
 */
@SuppressWarnings({"UnusedDeclaration"})
@Implements(Resources.class)
public class ShadowResources {
  private static boolean DEBUG = false;
  private static Resources system = null;

  private float density = 1.0f;
  private DisplayMetrics displayMetrics;
  private Display display;
  @RealObject Resources realResources;
  private ResourceLoader resourceLoader;

  @Resetter
  public static void reset() {
    for (Field field : Resources.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType().equals(LongSparseArray.class)) {
        try {
          field.setAccessible(true);
          LongSparseArray longSparseArray = (LongSparseArray) field.get(null);
          if (longSparseArray != null) {
            longSparseArray.clear();
          }
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  public static void setSystemResources(ResourceLoader systemResourceLoader) {
    AssetManager assetManager = Robolectric.newInstanceOf(AssetManager.class);
    ShadowAssetManager.bind(assetManager, null, systemResourceLoader);
    DisplayMetrics metrics = new DisplayMetrics();
    Configuration config = new Configuration();
    system = ShadowResources.bind(new Resources(assetManager, metrics, config), systemResourceLoader);
  }

  static Resources bind(Resources resources, ResourceLoader resourceLoader) {
    ShadowResources shadowResources = shadowOf(resources);
    if (shadowResources.resourceLoader != null) throw new RuntimeException("ResourceLoader already set!");
    shadowResources.resourceLoader = resourceLoader;
    return resources;
  }

  @Implementation
  public static Resources getSystem() {
    return system;
  }

  public static Resources createFor(ResourceLoader resourceLoader) {
    AssetManager assetManager = ShadowAssetManager.bind(Robolectric.newInstanceOf(AssetManager.class), null, resourceLoader);
    return bind(new Resources(assetManager, new DisplayMetrics(), new Configuration()), resourceLoader);
  }

  private TypedArray attrsToTypedArray(AttributeSet set, int[] attrs, int defStyleAttr, int themeResourceId, int defStyleRes) {
    /*
     * When determining the final value of a particular attribute, there are four inputs that come into play:
     *
     * 1. Any attribute values in the given AttributeSet.
     * 2. The style resource specified in the AttributeSet (named "style").
     * 3. The default style specified by defStyleAttr and defStyleRes
     * 4. The base values in this theme.
     */
    ResourceLoader resourceLoader = getResourceLoader();
    ShadowAssetManager shadowAssetManager = shadowOf(realResources.getAssets());
    String qualifiers = shadowAssetManager.getQualifiers();

    if (set == null) {
      set = new RoboAttributeSet(new ArrayList<Attribute>(), realResources, null);
    }
    Style defStyleFromAttr = null;
    Style defStyleFromRes = null;
    Style styleAttrStyle = null;
    Style theme = null;

    List<ShadowAssetManager.OverlayedStyle> overlayedStyles = ShadowAssetManager.getOverlayThemeStyles(themeResourceId);
    if (themeResourceId != 0) {
      // Load the style for the theme we represent. E.g. "@style/Theme.Robolectric"
      ResName themeStyleName = getResName(themeResourceId);
      if (DEBUG) System.out.println("themeStyleName = " + themeStyleName);

      theme = ShadowAssetManager.resolveStyle(resourceLoader, null, themeStyleName, shadowAssetManager.getQualifiers());

      if (defStyleAttr != 0) {
        // Load the theme attribute for the default style attributes. E.g., attr/buttonStyle
        ResName defStyleName = getResName(defStyleAttr);

        // Load the style for the default style attribute. E.g. "@style/Widget.Robolectric.Button";
        Attribute defStyleAttribute = getOverlayedThemeValue(defStyleName, theme, overlayedStyles);
        if (defStyleAttribute != null) {
          while (defStyleAttribute.isStyleReference()) {
            Attribute other = getOverlayedThemeValue(defStyleAttribute.getStyleReference(), theme, overlayedStyles);
            if (other == null) {
              throw new RuntimeException("couldn't dereference " + defStyleAttribute);
            }
            defStyleAttribute = other;

            // todo
            System.out.println("TODO: Not handling " + defStyleAttribute + " yet in ShadowResouces!");
          }

          if (defStyleAttribute.isResourceReference()) {
            ResName defStyleResName = defStyleAttribute.getResourceReference();
            defStyleFromAttr = ShadowAssetManager.resolveStyle(resourceLoader, theme, defStyleResName, shadowAssetManager.getQualifiers());
          }
        }
      }
    }

    int styleAttrResId = set.getStyleAttribute();
    if (styleAttrResId != 0) {
      ResName styleAttributeResName = getResName(styleAttrResId);
      while (styleAttributeResName.type.equals("attr")) {
        Attribute attrValue = getOverlayedThemeValue(styleAttributeResName, theme, overlayedStyles);
        if (attrValue.isResourceReference()) {
          styleAttributeResName = attrValue.getResourceReference();
        } else if (attrValue.isStyleReference()) {
          styleAttributeResName = attrValue.getStyleReference();
        }
      }
      styleAttrStyle = ShadowAssetManager.resolveStyle(resourceLoader, theme, styleAttributeResName, shadowAssetManager.getQualifiers());
    }

    if (defStyleRes != 0) {
      ResName resName = getResName(defStyleRes);
      if (resName.type.equals("attr")) {
        Attribute attributeValue = findAttributeValue(getResName(defStyleRes), set, styleAttrStyle, defStyleFromAttr, defStyleFromAttr, theme, overlayedStyles);
        if (attributeValue != null) {
          if (attributeValue.isStyleReference()) {
            resName = getOverlayedThemeValue(attributeValue.getStyleReference(), theme, overlayedStyles).getResourceReference();
          } else if (attributeValue.isResourceReference()) {
            resName = attributeValue.getResourceReference();
          }
        }
      }
      defStyleFromRes = ShadowAssetManager.resolveStyle(resourceLoader, theme, resName, shadowAssetManager.getQualifiers());
    }

    List<Attribute> attributes = new ArrayList<Attribute>();
    if (attrs == null) attrs = new int[0];
    for (int attr : attrs) {
      ResName attrName = tryResName(attr); // todo probably getResName instead here?
      if (attrName == null) continue;

      Attribute attribute = findAttributeValue(attrName, set, styleAttrStyle, defStyleFromAttr, defStyleFromRes, theme, overlayedStyles);
      while (attribute != null && attribute.isStyleReference()) {
        ResName otherAttrName = attribute.getStyleReference();
        if (theme == null) throw new RuntimeException("no theme, but trying to look up " + otherAttrName);
        attribute = getOverlayedThemeValue(otherAttrName, theme, overlayedStyles);
        if (attribute != null) {
          attribute = new Attribute(attrName, attribute.value, attribute.contextPackageName);
        }
      }

      if (attribute != null) {
        Attribute.put(attributes, attribute);
      }
    }

    TypedArray typedArray = createTypedArray(attributes, attrs);
    shadowOf(typedArray).positionDescription = set.getPositionDescription();
    return typedArray;
  }

  public TypedArray createTypedArray(List<Attribute> set, int[] attrs) {
    ResourceLoader resourceLoader = getResourceLoader();
    ResourceIndex resourceIndex = resourceLoader.getResourceIndex();
    String qualifiers = shadowOf(realResources.getAssets()).getQualifiers();

    CharSequence[] stringData = new CharSequence[attrs.length];
    int[] data = new int[attrs.length * ShadowAssetManager.STYLE_NUM_ENTRIES];
    int[] indices = new int[attrs.length + 1];
    int nextIndex = 0;

    List<Integer> wantedAttrsList = Util.intArrayToList(attrs);

    for (int i = 0; i < attrs.length; i++) {
      int offset = i * ShadowAssetManager.STYLE_NUM_ENTRIES;

      int attr = attrs[i];
      ResName attrName = resourceIndex.getResName(attr);
      if (attrName != null) {
        Attribute attribute = Attribute.find(set, attrName);
        TypedValue typedValue = new TypedValue();
        Converter.convertAndFill(attribute, typedValue, resourceLoader, qualifiers, true);

        if (attribute != null && !attribute.isNull()) {
          //noinspection PointlessArithmeticExpression
          data[offset + ShadowAssetManager.STYLE_TYPE] = typedValue.type;
          data[offset + ShadowAssetManager.STYLE_DATA] = typedValue.type == TypedValue.TYPE_STRING ? i : typedValue.data;
          data[offset + ShadowAssetManager.STYLE_ASSET_COOKIE] = typedValue.assetCookie;
          data[offset + ShadowAssetManager.STYLE_RESOURCE_ID] = typedValue.resourceId;
          data[offset + ShadowAssetManager.STYLE_CHANGING_CONFIGURATIONS] = typedValue.changingConfigurations;
          data[offset + ShadowAssetManager.STYLE_DENSITY] = typedValue.density;
          stringData[i] = typedValue.string;

          indices[nextIndex + 1] = i;
          nextIndex++;
        }
      }
    }

    indices[0] = nextIndex;

    return ShadowTypedArray.create(realResources, attrs, data, indices, nextIndex, stringData);
  }

  private Attribute findAttributeValue(ResName attrName, AttributeSet attributeSet, Style styleAttrStyle, Style defStyleFromAttr, Style defStyleFromRes, Style theme, List<ShadowAssetManager.OverlayedStyle> overlayedStyles) {
    String attrValue = attributeSet.getAttributeValue(attrName.getNamespaceUri(), attrName.name);
    if (attrValue != null) {
      if (DEBUG) System.out.println("Got " + attrName + " from attr: " + attrValue);
      return new Attribute(attrName, attrValue, "fixme!!!");
    }

    if (styleAttrStyle != null) {
      Attribute attribute = styleAttrStyle.getAttrValue(attrName);
      if (attribute != null) {
        if (DEBUG) System.out.println("Got " + attrName + " from styleAttrStyle: " + attribute);
        return attribute;
      }
    }

    // else if attr in defStyleFromAttr, use its value
    if (defStyleFromAttr != null) {
      Attribute attribute = defStyleFromAttr.getAttrValue(attrName);
      if (attribute != null) {
        if (DEBUG) System.out.println("Got " + attrName + " from defStyleFromAttr: " + attribute);
        return attribute;
      }
    }

    if (defStyleFromRes != null) {
      Attribute attribute = defStyleFromRes.getAttrValue(attrName);
      if (attribute != null) {
        if (DEBUG) System.out.println("Got " + attrName + " from defStyleFromRes: " + attribute);
        return attribute;
      }
    }

    // else if attr in theme, use its value
    if (theme != null) {
      return getOverlayedThemeValue(attrName, theme, overlayedStyles);
    }

    return null;
  }

  static Attribute getOverlayedThemeValue(ResName attrName, Style theme, List<ShadowAssetManager.OverlayedStyle> overlayedStyles) {
    Attribute attribute = theme.getAttrValue(attrName);

    if (overlayedStyles != null) {
      for (ShadowAssetManager.OverlayedStyle overlayedStyle : overlayedStyles) {
        Attribute overlayedAttribute = overlayedStyle.style.getAttrValue(attrName);
        if (overlayedAttribute != null && (attribute == null || overlayedStyle.force)) {
          attribute = overlayedAttribute;
        }
      }
    }

    if (attribute != null) {
      if (DEBUG) System.out.println("Got " + attrName + " from theme: " + attribute);
    }
    return attribute;
  }

  @Implementation
  public TypedArray obtainAttributes(AttributeSet set, int[] attrs) {
    return attrsToTypedArray(set, attrs, 0, 0, 0);
  }

  @Implementation
  public int getIdentifier(String name, String defType, String defPackage) {
    ResourceIndex resourceIndex = getResourceLoader().getResourceIndex();
    ResName resName = ResName.qualifyResName(name, defPackage, defType);
    Integer resourceId = resourceIndex.getResourceId(resName);
    if (resourceId == null) return 0;
    return resourceId;
  }

  @Implementation
  public String getResourceName(int resId) throws Resources.NotFoundException {
    return getResName(resId).getFullyQualifiedName();
  }

  @Implementation
  public String getResourcePackageName(int resId) throws Resources.NotFoundException {
    return getResName(resId).packageName;
  }

  @Implementation
  public String getResourceTypeName(int resId) throws Resources.NotFoundException {
    return getResName(resId).type;
  }

  @Implementation
  public String getResourceEntryName(int resId) throws Resources.NotFoundException {
    return getResName(resId).name;
  }

  private boolean isEmpty(String s) {
    return s == null || s.length() == 0;
  }

  private @NotNull ResName getResName(int id) {
    ResName resName = getResourceLoader().getResourceIndex().getResName(id);
    if (resName == null) {
      throw new Resources.NotFoundException("Unable to find resource ID #0x" + Integer.toHexString(id));
    }
    return resName;
  }

  private ResName tryResName(int id) {
    return getResourceLoader().getResourceIndex().getResName(id);
  }

  private String getQualifiers() {
    return shadowOf(realResources.getAssets()).getQualifiers();
  }

  @Implementation
  public CharSequence getText(int id) throws Resources.NotFoundException {
    CharSequence text = directlyOn(realResources, Resources.class).getText(id);
    return StringResources.escape(text.toString());
  }

  @Implementation
  public String getQuantityString(int id, int quantity, Object... formatArgs) throws Resources.NotFoundException {
    String raw = getQuantityString(id, quantity);
    return String.format(Locale.ENGLISH, raw, formatArgs);
  }

  @Implementation
  public String getQuantityString(int id, int quantity) throws Resources.NotFoundException {
    ResName resName = getResName(id);
    Plural plural = getResourceLoader().getPlural(resName, quantity, getQualifiers());
    String string = plural.getString();
    ShadowAssetManager shadowAssetManager = shadowOf(realResources.getAssets());
    TypedResource typedResource = shadowAssetManager.resolve(
        new TypedResource<String>(string, ResType.CHAR_SEQUENCE), getQualifiers(),
        new ResName(resName.packageName, "string", resName.name));
    return typedResource == null ? null : typedResource.asString();
  }

  @Implementation
  public InputStream openRawResource(int id) throws Resources.NotFoundException {
    return getResourceLoader().getRawValue(getResName(id));
  }

  @Implementation
  public AssetFileDescriptor openRawResourceFd(int id) throws Resources.NotFoundException {
    try {
      FileInputStream fis = (FileInputStream)openRawResource(id);
      return new AssetFileDescriptor(ParcelFileDescriptor.dup(fis.getFD()), 0, fis.getChannel().size());
    } catch (Exception e) {
      return null;
    }
  }

  public void setDensity(float density) {
    this.density = density;
    if (displayMetrics != null) {
      displayMetrics.density = density;
    }
  }

  public void setScaledDensity(float scaledDensity) {
    if (displayMetrics != null) {
      displayMetrics.scaledDensity = scaledDensity;
    }
  }

  public void setDisplay(Display display) {
    this.display = display;
    displayMetrics = null;
  }

  @Implementation
  public DisplayMetrics getDisplayMetrics() {
    if (displayMetrics == null) {
      if (display == null) {
        display = Robolectric.newInstanceOf(Display.class);
      }

      displayMetrics = new DisplayMetrics();
      display.getMetrics(displayMetrics);
    }
    displayMetrics.density = this.density;
    return displayMetrics;
  }

  @Implementation
  public XmlResourceParser getXml(int id) throws Resources.NotFoundException {
    ResName resName = getResName(id);
    Document document = getResourceLoader().getXml(resName, getQualifiers());
    if (document == null) {
      throw new Resources.NotFoundException();
    }
    return new XmlFileBuilder().getXml(document, resName.getFullyQualifiedName(), resName.packageName, getResourceLoader().getResourceIndex());
  }

  @HiddenApi @Implementation
  public XmlResourceParser loadXmlResourceParser(String file, int id, int assetCookie, String type) throws Resources.NotFoundException {
    String packageName = getResName(id).packageName;
    return XmlFileBuilder.getXmlResourceParser(file, packageName, getResourceLoader().getResourceIndex());
  }

  public ResourceLoader getResourceLoader() {
    if (resourceLoader == null) {
      resourceLoader = Robolectric.getShadowApplication().getResourceLoader();
    }
    return resourceLoader;
  }

  @Implements(Resources.Theme.class)
  public static class ShadowTheme {
    @RealObject Resources.Theme realTheme;
    protected Resources resources;
    private int styleResourceId;

    @Implementation
    public void applyStyle(int resid, boolean force) {
      if (styleResourceId == 0) {
        this.styleResourceId = resid;
      }

      ShadowAssetManager.applyThemeStyle(styleResourceId, resid, force);
    }

    @Implementation
    public void setTo(Resources.Theme other) {
      this.styleResourceId = shadowOf(other).styleResourceId;
    }

    public int getStyleResourceId() {
      return styleResourceId;
    }

    @Implementation
    public TypedArray obtainStyledAttributes(int[] attrs) {
      return obtainStyledAttributes(0, attrs);
    }

    @Implementation
    public TypedArray obtainStyledAttributes(int resid, int[] attrs) throws android.content.res.Resources.NotFoundException {
      return obtainStyledAttributes(null, attrs, 0, resid);
    }

    @Implementation
    public TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
      return shadowOf(getResources()).attrsToTypedArray(set, attrs, defStyleAttr, styleResourceId, defStyleRes);
    }

    Resources getResources() {
      // ugh
      return ReflectionHelpers.getFieldReflectively(realTheme, "this$0");
    }
  }

  @Implementation
  public final Resources.Theme newTheme() {
    Resources.Theme theme = directlyOn(realResources, Resources.class).newTheme();
    int themeId = ReflectionHelpers.getFieldReflectively(theme, "mTheme");
    shadowOf(realResources.getAssets()).setTheme(themeId, theme);
    return theme;
  }

  @HiddenApi @Implementation
  public Drawable loadDrawable(TypedValue value, int id) {
    ResName resName = tryResName(id);
    Drawable drawable = directlyOn(realResources, Resources.class, "loadDrawable", new ReflectionHelpers.ClassParameter(TypedValue.class, value),
        new ReflectionHelpers.ClassParameter(int.class, id));
    // todo: this kinda sucks, find some better way...
    if (drawable != null) {
      shadowOf(drawable).createdFromResId = id;
      if (drawable instanceof BitmapDrawable) {
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap != null) {
          ShadowBitmap shadowBitmap = shadowOf(bitmap);
          if (shadowBitmap.createdFromResId == -1) {
            shadowBitmap.setCreatedFromResId(id, resName);
          }
        }
      }
    }
    return drawable;
  }

  @Implements(Resources.NotFoundException.class)
  public static class ShadowNotFoundException {
    @RealObject Resources.NotFoundException realObject;

    private String message;

    public void __constructor__() {
    }

    public void __constructor__(String name) {
      this.message = name;
    }

    @Implementation
    public String toString() {
      return realObject.getClass().getName() + ": " + message;
    }
  }
}

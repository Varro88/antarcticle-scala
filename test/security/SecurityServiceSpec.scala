package security

import java.net.SocketTimeoutException
import java.sql.Timestamp

import models.UserModels.User
import models.database.UserRecord
import org.mockito.Matchers
import org.mockito.Matchers.{eq => mockEq}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.{MatchResult, Matcher}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.scalaz.ValidationMatchers
import org.specs2.specification.BeforeEach
import org.specs2.specification.mutable.ExecutionEnvironment
import repositories.UsersRepositoryComponent
import services.{ApplicationPropertiesServiceComponent, MailServiceComponent}
import util.FakeSessionProvider
import util.FakeSessionProvider.FakeSessionValue
import utils.SecurityUtil
import util.TestHelpers._
import validators.UserValidator

import scala.concurrent.Future
import scala.slick.jdbc.JdbcBackend
import scalaz._
import Scalaz._

class SecurityServiceSpec extends Specification
with ValidationMatchers with Mockito with BeforeEach
with ExecutionEnvironment {

  object service extends SecurityServiceComponentImpl with UsersRepositoryComponent
  with FakeSessionProvider with SecurityServiceComponent with AuthenticationManagerProvider
  with ApplicationPropertiesServiceComponent with MailServiceComponent {
    override val usersRepository = mock[UsersRepository]
    override val authenticationManager = mock[AuthenticationManager]
    override val userValidator = mock[UserValidator]
    override val mailService = mock[MailService]
    override val propertiesService = mock[ApplicationPropertiesService]

  }

  import service._

  override protected def before = {
    org.mockito.Mockito.reset(usersRepository)
    org.mockito.Mockito.reset(authenticationManager)
    org.mockito.Mockito.reset(userValidator)
    org.mockito.Mockito.reset(mailService)
  }

  def is(implicit ee: ExecutionEnv) = {

    def anySession = any[JdbcBackend#Session]

    "sign in" should {
      "be success" in {
        val username = "userdfsd"
        val password = "rerfev"
        val salt = Some("fakeSalt")
        val encodedPassword: String = SecurityUtil.encodePassword(password, salt)
        val email = "mail01@mail.zzz"
        val userInfo = UserInfo(username, password, email, "fn".some, "ln".some, active = true)
        val authUser = AuthenticatedUser(1, username, Authorities.User)
        val userFromDb = UserRecord(Some(1), username, encodedPassword, email,admin = false, salt)
        val userFromDb2 = UserRecord(Some(2), username.toUpperCase, encodedPassword, email, admin = false, salt)
        val usernameIgnoreCase: Matcher[String] = (_: String).equalsIgnoreCase(username)
        val fakeCreatedAt = new Timestamp(System.currentTimeMillis())

        def beMostlyEqualTo = (be_==(_: UserRecord)) ^^^ ((_: UserRecord).copy(
          salt = "salt".some,
          password = "pwd",
          uid = "uid",
          createdAt = fakeCreatedAt
        ))

        "return remember me token and authenticated user" in {
          authenticationManager.authenticate(username, password) returns Future.successful(userInfo.some)
          usersRepository.getByUsername(userInfo.username)(FakeSessionValue) returns Some(userFromDb)

          securityService.signInUser(username, password) must beSuccessful.await
        }

        "authenticated admin should have Admin authority" in {
          authenticationManager.authenticate(username, password) returns Future.successful(userInfo.some)
          usersRepository.getByUsername(userInfo.username)(FakeSessionValue) returns Some(userFromDb.copy(admin = true))

          val expected: (String, AuthenticatedUser) :=> MatchResult[_] = {
            case (_, user) if user.authority == Authorities.Admin => ok
          }

          securityService.signInUser(username, password) must beSuccessful.like(expected).await
        }


        "create new user when not exists" in {

          def withMockedAuthenticationManagerAndTokenProvider[T](doTest: => T) = {
            authenticationManager.authenticate(usernameIgnoreCase, ===(password)) returns Future.successful(userInfo.some)
            usersRepository.getByUsername(usernameIgnoreCase)(anySession) returns None

            doTest

            val expectedRecord = UserRecord(None, username, encodedPassword, "NA", admin = false, salt, "fn".some,
              "ln".some, active = true, createdAt = fakeCreatedAt)
            there was one(usersRepository).insert(beMostlyEqualTo(expectedRecord))(anySession)
          }

          "and username has a correct case" in {
            withMockedAuthenticationManagerAndTokenProvider {
              securityService.signInUser(username, password) must beSuccessful.await

              there was one(authenticationManager).authenticate(===(username), ===(password))
            }
          }
        }

        "update user when password does not match" in {

          def withMockedAuthenticationManagerAndTokenProvider[T](doTest: => T) = {
            authenticationManager.authenticate(username, password) returns Future.successful(userInfo.some)

            doTest

            there was no(usersRepository).insert(any[UserRecord])(anySession)
            val expectedRecord = UserRecord(Some(1), username, encodedPassword, email, admin = false, salt, "fn".some, "ln".some)
            there was one(usersRepository).update(beMostlyEqualTo(expectedRecord))(anySession)
          }

          val userWithEmptyPassword: UserRecord = userFromDb.copy(password = "")
          "and one user exists with case-insensitive username" in {
            withMockedAuthenticationManagerAndTokenProvider {
              usersRepository.getByUsername(userInfo.username)(FakeSessionValue) returns userWithEmptyPassword.some
              securityService.signInUser(username, password) must beSuccessful.await
            }
          }

          "and several users exist with case-insensitive username" in {
            withMockedAuthenticationManagerAndTokenProvider {
              usersRepository.getByUsername(userInfo.username)(FakeSessionValue) returns userWithEmptyPassword.some
              securityService.signInUser(username, password) must beSuccessful.await
            }
          }

        }

        "created user should have User authority" in {
          authenticationManager.authenticate(username, password) returns Future.successful(userInfo.some)
          usersRepository.getByUsername(userInfo.username)(FakeSessionValue) returns None

          val expected: (String, AuthenticatedUser) :=> MatchResult[_] = {
            case (_, user) if user.authority == Authorities.User => ok
          }

          securityService.signInUser(username, password) must beSuccessful.like(expected).await
        }

        "issue remember me token to authenticated user" in {
          val userId = 1
          authenticationManager.authenticate(username, password) returns Future.successful(userInfo.some)
          usersRepository.getByUsername(username)(FakeSessionValue) returns None
          usersRepository.insert(any[UserRecord])(Matchers.eq(FakeSessionValue)) returns userId
          usersRepository.updateRememberToken(mockEq(userId), anyString)(mockEq(FakeSessionValue)) returns true

          securityService.signInUser(username, password) must beSuccessful.await

          there was one(usersRepository).updateRememberToken(mockEq(userId), anyString)(mockEq(FakeSessionValue))
        }

        "trim username" in {
          authenticationManager.authenticate(username, password) returns Future.successful(userInfo.some)
          usersRepository.getByUsername(username)(FakeSessionValue) returns Some(userFromDb)

          val expected: (String, AuthenticatedUser) :=> MatchResult[_] = {
            case (_, user) if user.username == username => ok
          }

          securityService.signInUser(' ' + username + ' ', password) must beSuccessful.like(expected).await

          there was one(usersRepository).getByUsername(===(username))(anySession)
        }

      }

      "fail" in {
        "return validation error" in {
          authenticationManager.authenticate(anyString, anyString) returns Future.successful(None)
          usersRepository.getByUsername(anyString)(anySession) returns None

          securityService.signInUser("", "") must beFailing.await
        }
      }
    }

    "sign up" should {

      val username = "fakeUsername"
      val password = "p@$$w0rD"
      val email = "fake@email.net"
      val host = "http://fakehost:9000"
      val uuid = "fake-uuid"
      val user = User(username, email, password)

      def expectedUserInfo: Matcher[UserInfo] = { userInfo: UserInfo =>
        userInfo.username == username && userInfo.email == email
      }

      def expectedUserRecord: Matcher[UserRecord] = { record: UserRecord =>
        record.username == username && record.email == email
      }

      "be successful" in {
        userValidator.validate(user) returns user.successNel
        authenticationManager.register(any[UserInfo]) returns Future.successful(uuid.successNel)
        mailService.sendEmail(anyString, anyString, anyString) returns Future.successful(():Unit)
        usersRepository.insert(any[UserRecord])(anySession) returns 1

        val expected: String :=> MatchResult[_] = {case uid: String => uid must beEqualTo(uuid)}

        securityService.signUpUser(user, host) must beSuccessful.like{expected}.await

        there was one(userValidator).validate(user) andThen
          one(authenticationManager).register(expectedUserInfo) andThen
          one(mailService).sendEmail(===(email), anyString, anyString) andThen
          one(usersRepository).insert(expectedUserRecord)(anySession)
      }

      "fail when user is invalid" in {
        userValidator.validate(user) returns "error".failureNel

        securityService.signUpUser(user, host) must beFailing.await

        there was one(userValidator).validate(user)
        there was no(authenticationManager).register(expectedUserInfo)
        there was no(mailService).sendEmail(anyString, anyString, anyString)
        there was no(usersRepository).insert(any[UserRecord])(anySession)
      }

      "fail when user is not registered by authentication manager" in {
        userValidator.validate(user) returns user.successNel
        authenticationManager.register(any[UserInfo]) returns Future.successful(uuid.failureNel)

        securityService.signUpUser(user, host) must beFailing.await

        there was one(userValidator).validate(user) andThen
          one(authenticationManager).register(expectedUserInfo)
        there was no(mailService).sendEmail(anyString, anyString, anyString)
        there was no(usersRepository).insert(any[UserRecord])(anySession)
      }

      "fail when authentication manager is not available" in {
        userValidator.validate(user) returns user.successNel
        authenticationManager.register(any[UserInfo]) returns Future.failed(new SocketTimeoutException())

        securityService.signUpUser(user, host) must throwA[Exception].like{
          case e: Exception => e.isInstanceOf[SocketTimeoutException] must beTrue
        }.await

        there was one(userValidator).validate(user) andThen
          one(authenticationManager).register(expectedUserInfo)
        there was no(mailService).sendEmail(anyString, anyString, anyString)
        there was no(usersRepository).insert(any[UserRecord])(anySession)
      }
    }

    "activation" should {
      val uid = "fake-user-uid"
      val userRecord = Some(UserRecord(Some(1), "username", "passW0rD", "email@e,ai.net", admin = false, Some("saLt")))

      "be successful" in {
        authenticationManager.activate(uid) returns Future.successful(().successNel)
        usersRepository.getByUID(===(uid))(anySession) returns userRecord

        securityService.activateUser(uid) must beSuccessful.await

        there was one(authenticationManager).activate(===(uid)) andThen
          one(usersRepository).getByUID(===(uid))(anySession) andThen
          one(usersRepository).update(any[UserRecord])(anySession)
      }

      "fail if user is not activated by authentication manager" in {
        authenticationManager.activate(uid) returns Future.successful("errror".failureNel)

        securityService.activateUser(uid) must beFailing.await

        there was one(authenticationManager).activate(===(uid))
        there was no(usersRepository).getByUID(===(uid))(anySession)
        there was no(usersRepository).update(any[UserRecord])(anySession)
      }

      "fail when authentication manager is not available" in {
        authenticationManager.activate(uid) returns Future.failed(new SocketTimeoutException())

        securityService.activateUser(uid) must throwA[Exception].like{
          case e: Exception => e.isInstanceOf[SocketTimeoutException] must beTrue
        }.await

        there was one(authenticationManager).activate(===(uid))
        there was no(usersRepository).getByUID(===(uid))(anySession)
        there was no(usersRepository).update(any[UserRecord])(anySession)
      }
    }

  }
}
